package onlinetours.test.util

/**
 * Выполняет обращения к API сайта, что позволяет запустить поиск или стартовать загрузку детальной информации
 * о перелётах для выбранного тура
 */

import akka.actor.Actor
import onlinetours.test.Flights.SearchQuery

class ApiActor extends Actor {
    def receive = ???
}

object ApiActor {

    /**
     * Стартует поиск. При этом в БД создается строка, определяющая состояние текущего поиска. По мере нахождения новых
     * туров, инкрементится значение search.offersFound. Когда все провайдеры туров опрошены, поиск переходит в состояние
     * completed (см. DbActor.SearchStatus)
     *
     * Поиск завершается за несколько секунд (5-20), в пределах одной обработки запроса данного сервиса можно запустить только один поиск
     */
    case class StartSearch(query: SearchQuery)
    case class SearchStarted(searchId: String)  // в ответ на StartSearch

    /**
     * Стартует загрузку детальной информации о перелётах заданного тура. По выполнению загрузки, происходит обновление
     * информации о туре в NoSQL БД. Т.е. необходимо периодически загружать Tour из NoSQL БД, чтобы дождаться момента,
     * когда поля тура departFlights, arrivalFlights будут заполнены
     *
     * Загрузка завершается за несколько секунд (5-20), в пределах обработки запроса данного сервиса можно запустить
     * несколько загрузок перелётов (но желательно как можно меньше)
     */
    case class StartFlightLoading(tourId: String)
    case class FlightLoadingStarted(tourId: String) // в ответ на StartFlightLoading, получение данного message
                                                    // не указывает на то, что перелёты загружены - только на то,
                                                     // что их загрузка стартовала
}
