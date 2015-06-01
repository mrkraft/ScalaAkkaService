package onlinetours.test

import akka.actor.{Actor, ActorRef}
import onlinetours.test.Flights._
import onlinetours.test.SearchActor.{HandleException, ReturnSearchResult}
import onlinetours.test.util.ApiActor.{FlightLoadingStarted, SearchStarted, StartFlightLoading, StartSearch}
import onlinetours.test.util.DbActor._
import onlinetours.test.util.ToursDbActor._

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
 * Актор, который необходимо реализовать
 *
 * @param request
 * @param requester актор, который ждёт ответа (сообщения Flights.Request) не более, чем через 30 секунд, после инстанциирования актора SearchActor
 * @param db ссылка на DbActor
 * @param tourDb ссылка на TourDbActor
 * @param api ссылка на ApiActor
 */
class SearchActor(request: Request, requester: ActorRef, db: ActorRef, tourDb: ActorRef, api: ActorRef) extends Actor {

    val system = context.system
    import system.dispatcher

    val DELAY_IN_MS: Int = 100
    val MAX_SEARCH_TIME: Int = 30000

    var searchQuery: SearchQuery = null
    var interimResultTour: DbTour = null    //промежуточный результат
    var resultTour: DbTour = null
    var searchCompleted: Boolean = false

    override def preStart(): Unit = {
        system.scheduler.scheduleOnce(MAX_SEARCH_TIME milliseconds, self, ReturnSearchResult())     //по истечении максимального времени поиска - вернуть то, что на тот момент нашли
        db ! FetchRegionIdByIata(List(request.departureIata, request.arrivalIata))
    }

    def receive = {

        /**
         * Получаем идентификаторы регионов по iata-кодам,
         * создаём searchQuery, запускаем оба поиска
         */
        case RegionByIataFetched(regionIdByIata) =>
            // не понятно как выражается отсутствие Id для Iata кода в regionIdByIata,
            // поэтому проверяю количество найденных
            if (regionIdByIata.size == 2) {
                searchQuery = createSearchQuery(request, regionIdByIata)
                api ! StartSearch(searchQuery)
                tourDb ! FindCheapestTour(searchQuery)
            } else {
                self ! HandleException(new RuntimeException("Can't convert Iata codes - FetchRegionIdByIata"))
            }


        /**
         * Поиск в БД запущен
         * Инициируем проверку статуса поиска
         */
        case SearchStarted(searchId) =>
            db ! FetchSearchStatus(searchId)

        /**
         * Получен статус поиска
         */
        case SearchStatus(searchId: String, completed: Boolean, toursFound: Int) =>
            if (completed) {
                this.searchCompleted = true
            } else {
                system.scheduler.scheduleOnce(DELAY_IN_MS milliseconds, db, FetchSearchStatus(searchId))
            }


        /**
         * Результат поиска в NoSQL БД
         */
        case Cheapest(tour) =>
            tour match {
                case Some(dbTour) =>
                    if (interimResultTour == null || interimResultTour.tourId != dbTour.tourId) {   //нашли первый или более дешевый тур
                        interimResultTour = dbTour;
                        if (!dbTour.hasFlightsInformation) {            //нет информации о перелётах - запускам загрузку информации о перелётах
                            api ! StartFlightLoading(dbTour.tourId)
                        } else {
                            if (resultTour == null || dbTour.minCost < resultTour.minCost)  //информация о перелётах присутствует - сохраняем первый или более дешевый результат
                                resultTour = dbTour
                        }
                    } else {
                        if (searchCompleted) {
                            if (resultTour.tourId == dbTour.tourId) //после окончания поиска в БД - получили тур, который уже обработали и сохранили в результат
                                                                    //инициируем отправку результата
                                self ! ReturnSearchResult()
                        }
                    }

                case None =>
            }
            if (!searchCompleted)
                system.scheduler.scheduleOnce(DELAY_IN_MS milliseconds, tourDb, FindCheapestTour(searchQuery))  //периодический поиск лучших туров


        /**
         * Загрузка информации о перелётах запущена
         * Инициируем проверку результатов загрузки через 5с (минимальное время загрузки)
         */
        case FlightLoadingStarted(tourId) =>
            system.scheduler.scheduleOnce(5000 milliseconds, tourDb, FetchTour(tourId))

        /**
         * Получена информация по туру
         * Проверяем информацию о перелётах
         */
        case TourFetched(tour) =>
            tour match {
                case Some(dbTour) =>
                    if (interimResultTour != null && interimResultTour.tourId == dbTour.tourId) {       //лучший найденный тур не изменился
                        if (!dbTour.hasFlightsInformation)          //если информация ещё не найдена - ждём
                            system.scheduler.scheduleOnce(DELAY_IN_MS milliseconds, tourDb, FetchTour(dbTour.tourId))
                        else
                        if (resultTour == null || dbTour.minCost < resultTour.minCost) {    //информация найдена - сохраняем/заменяем результат
                            resultTour = dbTour
                            if (searchCompleted)            //поиск в бд закончен, следовательно это лучший результат - инициируем отправку результата
                                self ! ReturnSearchResult()
                        }
                    }

                case None =>
            }


        /**
         * Отправка результата
         * Запрашиваем iata коды по именам авиакомпаний
         */
        case ReturnSearchResult() =>
            if (resultTour != null && resultTour.isValid) {
                db ! FetchAviacompanyIataByName(resultTour.getAviacompanyNames)
            } else {
                self ! HandleException(new RuntimeException("Valid result is not found"))     //по истечении времени результаты не найдены
            }

        /**
         * Получены iata коды по именам авиакомпаний
         * Создаём Response, отправляем результат, останавливаем работу данного актора
         */
        case AviacompanyIataByNameFetched(iataByName: Map[String, String]) =>
            val tour = resultTour.toTour(iataByName)
            requester ! Response(Success(tour))
            system.stop(self)

        /**
         * Обработка ошибок
         */
        case HandleException(ex) =>
            requester ! Response(Failure(ex))
            system.stop(self)

    }
}

object SearchActor {

    case class ReturnSearchResult()

    case class HandleException(ex: Exception)
}
