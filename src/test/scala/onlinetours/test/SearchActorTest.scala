package onlinetours.test

import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import onlinetours.test.Flights._
import onlinetours.test.util.ApiActor.{FlightLoadingStarted, StartFlightLoading, StartSearch}
import onlinetours.test.util.DbActor._
import onlinetours.test.util.ToursDbActor._
import org.joda.time.{LocalDate, LocalTime}
import org.scalatest.FreeSpec

import scala.concurrent.duration.DurationInt


class SearchActorTest extends FreeSpec {
    implicit val system: ActorSystem = ActorSystem("test")

    val today = LocalDate.now()

    trait Fixture {
        val request: Request = Request("SVO", "SSH", today, today plusDays 5)
        val searchQuery: SearchQuery = createSearchQuery(request, Map(("SVO", 1), ("SSH", 2)))

        val departDbFlight1 = DbFlight("Трансаэро", today, Some(LocalTime.MIDNIGHT))
        val arrivalDbFlight1 = DbFlight("Аэрофлот", today plusDays 5, Some(LocalTime.MIDNIGHT))
        val dbTour1 = DbTour("dbTour1 cheapest", "hotel1", 100000, Some(List(departDbFlight1)), Some(List(arrivalDbFlight1)))

        val departDbFlight2 = DbFlight("Трансаэро", today, Some(LocalTime.MIDNIGHT))
        val arrivalDbFlight2 = DbFlight("Аэрофлот", today plusDays 5, Some(LocalTime.MIDNIGHT))
        val dbTour2 = DbTour("dbTour2", "hotel2", 100500, Some(List(departDbFlight2)), Some(List(arrivalDbFlight2)))

        val dbTour1NoFlights = DbTour("dbTour1 cheapest", "hotel1", 100000, None, None)

        val dbActor = TestProbe()
        val tourDbActor = TestProbe()
        val apiActor = TestProbe()
        val requester = TestProbe()

        val searchActor = system.actorOf(Props(new SearchActor(request, requester.ref, dbActor.ref, tourDbActor.ref, apiActor.ref)))
    }

    "обрабатывает запрос и отвечает самым дешевым туром" in new Fixture {
        //получаем коды регионов и запускаем поиски
        dbActor.expectMsg(FetchRegionIdByIata(List(request.departureIata, request.arrivalIata)))
        dbActor.send(searchActor, RegionByIataFetched(Map(("SVO", 1), ("SSH", 2))))
        apiActor.expectMsg(StartSearch(searchQuery))
        tourDbActor.expectMsg(FindCheapestTour(searchQuery))

        //получаем тур из NoSQL БД - сохраняем его
        tourDbActor.send(searchActor, Cheapest(Some(dbTour2)))

        //поиск в БД закончился
        dbActor.send(searchActor, SearchStatus("searchId_1", true, 0))

        //запрашиваем в NoSQL БД тур и находим более дешевый без информации о перелётах
        tourDbActor.expectMsg(FindCheapestTour(searchQuery))
        tourDbActor.send(searchActor, Cheapest(Some(dbTour1NoFlights)))

        //запускаем поиск информации о перелётах, находим и сохраняем как лучший
        apiActor.expectMsg(StartFlightLoading(dbTour1NoFlights.tourId))
        apiActor.send(searchActor, FlightLoadingStarted(dbTour1NoFlights.tourId))
        tourDbActor.expectMsg(6 seconds, FetchTour(dbTour1NoFlights.tourId))
        tourDbActor.send(searchActor, TourFetched(Some(dbTour1)))

        //получаем Iata коды по именам авиакомпаний и отправляем результат
        dbActor.expectMsg(FetchAviacompanyIataByName(List("Трансаэро", "Аэрофлот")))
        dbActor.send(searchActor, AviacompanyIataByNameFetched(Map(("Трансаэро", "UN"), ("Аэрофлот", "SU"))))

        requester.expectMsg(10 seconds, Response(scala.util.Success(Tour("dbTour1 cheapest", "hotel1", 100000,
            List(Flight(Aviacompany("Трансаэро", Some("UN")), today.toLocalDateTime(LocalTime.MIDNIGHT))),
            List(Flight(Aviacompany("Аэрофлот", Some("SU")), (today plusDays 5).toLocalDateTime(LocalTime.MIDNIGHT))
            )))))
    }

    /**
     * Тест не работает, сообщения совпадают, но expectMsg использует для сравнения ==, что при сравнении
     * Exception'ов даёт не правильный результат
     */
    /*"отвечает ошибкой, если iata-регион не найден" in new Fixture {

        //получаем не правильные коды регионов
        dbActor.expectMsg(FetchRegionIdByIata(List(request.departureIata, request.arrivalIata)))
        dbActor.send(searchActor, RegionByIataFetched(Map(("SVO", 1))))

        requester.expectMsg(Response(scala.util.Failure(new RuntimeException("Can't convert Iata codes - FetchRegionIdByIata"))))
    }*/

    "отвечает туром из кэша, если поиск не возвратил результатов" in new Fixture {

        //получаем коды регионов и запускаем поиски
        dbActor.expectMsg(FetchRegionIdByIata(List(request.departureIata, request.arrivalIata)))
        dbActor.send(searchActor, RegionByIataFetched(Map(("SVO", 1), ("SSH", 2))))
        apiActor.expectMsg(StartSearch(searchQuery))
        tourDbActor.expectMsg(FindCheapestTour(searchQuery))

        //получаем тур из NoSQL БД - сохраняем его
        tourDbActor.send(searchActor, Cheapest(Some(dbTour2)))

        //поиск в БД закончился
        dbActor.send(searchActor, SearchStatus("searchId_1", true, 0))

        //запрашиваем в NoSQL БД тур и находим тот же самый
        tourDbActor.expectMsg(FindCheapestTour(searchQuery))
        tourDbActor.send(searchActor, Cheapest(Some(dbTour2)))

        //получаем Iata коды по именам авиакомпаний и отправляем результат
        dbActor.expectMsg(FetchAviacompanyIataByName(List("Трансаэро", "Аэрофлот")))
        dbActor.send(searchActor, AviacompanyIataByNameFetched(Map(("Трансаэро", "UN"), ("Аэрофлот", "SU"))))

        requester.expectMsg(Response(scala.util.Success(Tour("dbTour2", "hotel2", 100500,
            List(Flight(Aviacompany("Трансаэро", Some("UN")), today.toLocalDateTime(LocalTime.MIDNIGHT))),
            List(Flight(Aviacompany("Аэрофлот", Some("SU")), (today plusDays 5).toLocalDateTime(LocalTime.MIDNIGHT))
            )))))
    }

    "отвечает туром из кэша, если поиск не завершился в установленное время" in new Fixture {

        //получаем коды регионов и запускаем поиски
        dbActor.expectMsg(FetchRegionIdByIata(List(request.departureIata, request.arrivalIata)))
        dbActor.send(searchActor, RegionByIataFetched(Map(("SVO", 1), ("SSH", 2))))
        apiActor.expectMsg(StartSearch(searchQuery))
        tourDbActor.expectMsg(FindCheapestTour(searchQuery))

        //получаем тур из NoSQL БД - сохраняем его
        tourDbActor.send(searchActor, Cheapest(Some(dbTour2)))

        //ждём максимальное время выполнения запроса
        Thread.sleep(30000)

        //получаем Iata коды по именам авиакомпаний и отправляем результат
        dbActor.expectMsg(FetchAviacompanyIataByName(List("Трансаэро", "Аэрофлот")))
        dbActor.send(searchActor, AviacompanyIataByNameFetched(Map(("Трансаэро", "UN"), ("Аэрофлот", "SU"))))

        requester.expectMsg(35 seconds,  Response(scala.util.Success(Tour("dbTour2", "hotel2", 100500,
            List(Flight(Aviacompany("Трансаэро", Some("UN")), today.toLocalDateTime(LocalTime.MIDNIGHT))),
            List(Flight(Aviacompany("Аэрофлот", Some("SU")), (today plusDays 5).toLocalDateTime(LocalTime.MIDNIGHT))
            )))))
    }

    "если получение информации о перелётах не завершено в установленное время, отвечает наиболее дешевым из имеющихся с уже известными перелётами" in new Fixture {

        //получаем коды регионов и запускаем поиски
        dbActor.expectMsg(FetchRegionIdByIata(List(request.departureIata, request.arrivalIata)))
        dbActor.send(searchActor, RegionByIataFetched(Map(("SVO", 1), ("SSH", 2))))
        apiActor.expectMsg(StartSearch(searchQuery))
        tourDbActor.expectMsg(FindCheapestTour(searchQuery))

        //получаем тур из NoSQL БД - сохраняем его
        tourDbActor.send(searchActor, Cheapest(Some(dbTour2)))

        //поиск в БД закончился
        dbActor.send(searchActor, SearchStatus("searchId_1", true, 0))

        //запрашиваем в NoSQL БД тур и находим более дешевый без информации о перелётах
        tourDbActor.expectMsg(FindCheapestTour(searchQuery))
        tourDbActor.send(searchActor, Cheapest(Some(dbTour1NoFlights)))

        //запускаем поиск информации о перелётах
        apiActor.expectMsg(StartFlightLoading(dbTour1NoFlights.tourId))
        apiActor.send(searchActor, FlightLoadingStarted(dbTour1NoFlights.tourId))
        tourDbActor.expectMsg(6 seconds, FetchTour(dbTour1NoFlights.tourId))

        //ждём максимальное время выполнения запроса
        Thread.sleep(25000)

        //получаем Iata коды по именам авиакомпаний и отправляем результат
        dbActor.expectMsg(FetchAviacompanyIataByName(List("Трансаэро", "Аэрофлот")))
        dbActor.send(searchActor, AviacompanyIataByNameFetched(Map(("Трансаэро", "UN"), ("Аэрофлот", "SU"))))

        requester.expectMsg(35 seconds, Response(scala.util.Success(Tour("dbTour2", "hotel2", 100500,
            List(Flight(Aviacompany("Трансаэро", Some("UN")), today.toLocalDateTime(LocalTime.MIDNIGHT))),
            List(Flight(Aviacompany("Аэрофлот", Some("SU")), (today plusDays 5).toLocalDateTime(LocalTime.MIDNIGHT))
            )))))
    }

    "ANOTHER отвечаем наиболее дешевым туром, успевшим подгрузить перелёты, если самый дешевый не успел" in new Fixture {
        val dbTour2NoFlights = dbTour2.copy(arrivalFlights = None, departFlights = None)

        //получаем коды регионов и запускаем поиски
        dbActor.expectMsg(FetchRegionIdByIata(List(request.departureIata, request.arrivalIata)))
        dbActor.send(searchActor, RegionByIataFetched(Map(("SVO", 1), ("SSH", 2))))
        apiActor.expectMsg(StartSearch(searchQuery))
        tourDbActor.expectMsg(FindCheapestTour(searchQuery))

        //получаем тур из NoSQL БД без перелётов
        tourDbActor.send(searchActor, Cheapest(Some(dbTour2NoFlights)))

        // ожидаем старта актуализации по нему
        apiActor.expectMsg(StartFlightLoading(dbTour2NoFlights.tourId))
        apiActor.send(searchActor, FlightLoadingStarted(dbTour2NoFlights.tourId))

        //поиск в БД закончился
        dbActor.send(searchActor, SearchStatus("searchId_1", true, 0))

        //запрашиваем в NoSQL БД тур и находим более дешевый без информации о перелётах
        tourDbActor.expectMsg(FindCheapestTour(searchQuery))
        tourDbActor.send(searchActor, Cheapest(Some(dbTour1NoFlights)))

        //запускаем поиск информации о перелётах
        apiActor.expectMsg(StartFlightLoading(dbTour1NoFlights.tourId))
        apiActor.send(searchActor, FlightLoadingStarted(dbTour1NoFlights.tourId))

        tourDbActor.expectMsgAllOf(6 seconds, FetchTour(dbTour2NoFlights.tourId), FetchTour(dbTour1NoFlights.tourId))

        // предыдущий (не самый дешевый) тур закончил загрузку перелётов

        tourDbActor.send(searchActor, TourFetched(Some(dbTour2)))

        //ждём максимальное время выполнения запроса
        Thread.sleep(25000)

        //получаем Iata коды по именам авиакомпаний и отправляем результат
        dbActor.expectMsg(FetchAviacompanyIataByName(List("Трансаэро", "Аэрофлот")))
        dbActor.send(searchActor, AviacompanyIataByNameFetched(Map(("Трансаэро", "UN"), ("Аэрофлот", "SU"))))

        requester.expectMsg(35 seconds, Response(scala.util.Success(Tour("dbTour2", "hotel2", 100500,
            List(Flight(Aviacompany("Трансаэро", Some("UN")), today.toLocalDateTime(LocalTime.MIDNIGHT))),
            List(Flight(Aviacompany("Аэрофлот", Some("SU")), (today plusDays 5).toLocalDateTime(LocalTime.MIDNIGHT))
            )))))
    }
}
