package onlinetours.test.util

import akka.actor.Actor
import onlinetours.test.Flights.{Aviacompany, Flight, Tour, SearchQuery}
import org.joda.time.{LocalDate, LocalTime}

/**
 * Взаимодействует с NoSQL БД, в которой хранятся все найденные туры
 * БД автоматически удаляет устаревшие туры
 */
class ToursDbActor extends Actor {
    def receive = ???
}

object ToursDbActor {

    /**
     * Найти самый дешевый тур, удовлетворяющий поисковому запросу
     */
    case class FindCheapestTour(query: SearchQuery)
    case class Cheapest(tour: Option[DbTour])   // в ответ на FindCheapestTour. Т.е. в базе могут и отсутствовать туры по данному поисковому запросу

    /**
     * Загрузить отдельный тур. Например, чтобы проверить, не появилась ли в туре информация о перелётах
     */
    case class FetchTour(tourId: String)
    case class TourFetched(tour: Option[DbTour])    // тур может быть не найден в NoSQL БД,
                                                    // например если тур был удалён из базы, как устаревший (удаляется спустя пол часа после создания)

    /**
     *
     * @param tourId уникальный идентификатор тура в NoSQL БД
     * @param hotelName
     * @param minCost
     * @param departFlights перелёты обернуты в Option, т.е. информация о перелётах отсутствует для любых только что найденных туров.
     *                      Чтобы её получить, нужно вызвать ApiActor.StartFlightLoading
     * @param arrivalFlights
     */
    case class DbTour(tourId: String, hotelName: String, minCost: Int,
                      departFlights: Option[Traversable[DbFlight]], arrivalFlights: Option[Traversable[DbFlight]]) {

        def hasFlightsInformation: Boolean = departFlights.isDefined && arrivalFlights.isDefined

        def isValid: Boolean = departFlights.isDefined && arrivalFlights.isDefined &&
                departFlights.get.nonEmpty && arrivalFlights.get.nonEmpty

        def getAviacompanyNames: Traversable[String] =
            (departFlights.getOrElse(Traversable()) ++ arrivalFlights.getOrElse(Traversable())).map(_.aviacompanyName)

        def toTour(iataByName: Map[String, String]): Tour = {

            def getFlights(dbFlights: Option[Traversable[DbFlight]]): Traversable[Flight] =
				dbFlights.getOrElse(Traversable()).map(_.toFlight(iataByName))

            Tour(tourId, hotelName, minCost, getFlights(departFlights), getFlights(arrivalFlights))
        }
    }

    case class DbFlight(aviacompanyName: String, date: LocalDate, time: Option[LocalTime]) {

        def toFlight(iataByName: Map[String, String]): Flight = {
            Flight(
                Aviacompany(aviacompanyName, iataByName.get(aviacompanyName)),
                date.toLocalDateTime(time.getOrElse(LocalTime.MIDNIGHT)))
        }
    }

}
