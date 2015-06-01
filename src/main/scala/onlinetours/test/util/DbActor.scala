package onlinetours.test.util

/**
 * Взаимодействует с реляционной БД. Вся информация, необходимая для работы сервиса, хранится в БД
 * (кроме туров, которые хранятся в NoSQL БД, см. актор ToursDbActor)
 */

import akka.actor.Actor

class DbActor extends Actor {
    def receive = ???
}

object DbActor {

    case class FetchRegionIdByIata(regionIatas: Traversable[String])
    case class RegionByIataFetched(regionIdByIata: Map[String, Long])   // в ответ на FetchRegionIdByIata

    case class FetchAviacompanyIataByName(aviacompanies: Traversable[String])
    case class AviacompanyIataByNameFetched(iataByName: Map[String, String])    // в ответ на FetchAviacompanyIataByName

    /**
     * После старта поиска (см. ApiActor.StartSearch), по полученному id поиска можно опрашивать состояние поиска
     */
    case class FetchSearchStatus(searchId: String)

    /**
     * @param searchId
     * @param completed если completed=true, то поиск завершен и новых туров, удовлетворяющих условиям поиска, больше не будет поступать в БД
     * @param toursFound кол-во туров, найденных и сохранённых в NoSQL БД в рамках данного поиска
     */
    case class SearchStatus(searchId: String, completed: Boolean, toursFound: Int)  // в ответ на FetchSearchStatus
}
