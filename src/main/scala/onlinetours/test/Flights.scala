package onlinetours.test

import org.joda.time.{Days, LocalDate, LocalDateTime}

import scala.util.Try

/**
 * Приложение по поиску самого дешевого перелёта туда-обратно с проживанием в отеле.
 * Нужно выдать по возможности самый дешевый результат в течение 30 секунд.
 * Либо выдать любой имеющийся результат. Т.е. невыдача результата хуже, чем выдача не самого дешевого результата
 *
 * Акторы DbActor, ToursDbActor, ApiActor считать уже реализованными. Протокол работы с ними описан в
 * объектах-компаньонах соответствующих акторов.
 *
 * - DbActor взаимодействует с реляционной БД
 * - ToursDbActor взаимодействует с NoSQL БД, в которой попадают все найденные туры
 * Туры возвращаются как объекты класса DbTour
 * - ApiActor взаимодействует с API сайта (общее для данного сервиса и для js-скриптов сайта), умеет запускать поиск
 * и запускать загрузку детальной информации о перелётах. Туры, найденные через поиск никогда не имеют детальной
 * информации о перелётах. Информация о перелётах находится отдельным запросом (см. ApiActor.StartFlightLoading)
 *
 * Необходимо реализовать актор SearchActor, которвый руководит поиском. Очередность его работы такова:
 *
 * 1) в конструкторе актор SearchActor получает Flights.Request (и ссылки на вышеописанные акторы, берущие на себя общение с внешними сервисами)
 *
 * 2) Flights.Request содержит iata-коды городов вылета/прилёта, однако все сервисы работают с идентификаторами регионов -
 * нужно преобразовать их в идентификаторы при помощи DbActor.FetchRegionIdByIata
 *
 * 3) в NoSQL базе данных хранятся DbTour, в т.ч. полученные в результате предыдущих поисков
 * (т.е. некий кэш туров). Поэтому имеет смысл опросить кэш и получить самый дешевый тур из кэша
 *
 * 4) Тур из кэша может как содержать так и не содержать детальную информацию о перелётах
 * (авиакомпания, аэропорты, время и т.д.), причем чаще всего не содержит.
 * Необходимо запустить загрузку информации о перелётах через ApiActor.StartFlightLoading
 *
 * Тут и далее: тур не содержит информации о перелётах, если его поля departFlights, arrivalFlights равны None
 *
 * 5) Примечание: на момент получения сообщения ApiActor.FlightLoadingStarted (ответ на ApiActor.StartFlightLoading) тур еще не получил информацию о перелётах
 * (получение ответного сообщения лишь сигнализирует, что загрузка перелётов успешно стартовала). Сторонний сервис загрузит
 * информацию о перелётах и обновит запись тура в NoSQL БД. Необходимо периодически опрашивать данные тура в БД
 * (при помощи ToursDbActor.FetchTour), чтобы обнаружить момент, когда данные о перелётах появились
 *
 * 6) Одновременно с поиском по кэшу, следует стартовать поиск туров через ApiActor.StartSearch
 *
 * 7) Найденные через поиск туры также попадают в NoSQL БД и могут быть запрошены через ToursDbActor.FindCheapestTour.
 * Если самый дешевый тур, найденный в процессе поиска, дешевле тура, который ранее был получен непосредственно из кэша,
 * то этот тур следует выдать в качестве ответа Flights.Response (предварительно выполнив загрузку информации о перелётах, как описано выше).
 *
 * 8) Ответить необходимо туром с заполненной информацией о перелётах - ответ с туром без данной информации некорректен
 *
 * 9) Непосредственно перед выдачей ответа, нужно перевести имена авиакомпаний в их iata коды (см. DbActor.FetchAviacompanyIataByName)
 *
 * 10) Отправить аргументу requester сообщение Flights.Response с найденным туром. Соответственно, если в процессе обработки
 * запроса возникли ошибки, делающие обработку запроса невозможной, следует выдать ошибку requester-у
 * через то же сообщение и остановить актор SearchActor
 */
object Flights {

    def main(args: Array[String]) {

    }

    /**
     * Описывает поисковый запрос. Поисковые запросы могут быть выполнены через API сайта и через кэш туров в NoSQL базе.
     * Поиск через API сайта происходит сравнительно небыстро (секунды) и кладёт найденные результаты в NoSQL базу
     * По NoSQL базе также можно запускать поиск, который очень быстро (доли секунды) возвращает самый дешевый тур,
     * соответствующий запросу, но не обязательно найденный в рамках этого запроса - т.е. NoSQL база представляет собой
     * некий кэш туров
     */
    case class SearchQuery(departure: Long, arrival: Long, startDate: LocalDate, duration: Int)

    def createSearchQuery(request: Request, regionIdByIata: Map[String, Long]): SearchQuery =
        SearchQuery(
            regionIdByIata.get(request.departureIata).get,
            regionIdByIata.get(request.arrivalIata).get,
            request.start,
            Days.daysBetween(request.start, request.end).getDays)

    /**
     * SearchActor (который необходимо реализовать) получает данный реквест в конструкторе, не более чем через 30 он
     * должен выдать Response актору requester (ссылку на который он также получает при создании)
     *
     * @param departureIata IATA код аэропорта вылета, некоторый аэропорт России
     * @param arrivalIata IATA код прибытия, аэропорта страны, где проходит проживание в отеле
     * @param start дата вылета из России
     * @param end дата прибытия обратно в Россию
     */
    case class Request(departureIata: String, arrivalIata: String, start: LocalDate, end: LocalDate)

    case class Response(cheapest: Try[Tour])

    /**
     * Представление туров
     */
    case class Tour(tourId: String, // уникальный идентификатор тура в NoSQL БД
                    hotelName: String,
                    minCost: Int, // стоимость тура с самым дешевым перелётом
                    departFlights: Traversable[Flight],
                    arrivalFlights: Traversable[Flight])

    /**
     * в NoSQL БД авиакомпании хранятся по именам, однако перед выдачей в Response нужно актуализировать их поле iata
     * см. DbActor.FetchAviacompanyIataByName
     */
    case class Aviacompany(name: String, iata: Option[String])

    case class Flight(aviacompany: Aviacompany, date: LocalDateTime)

}
