##### Заметки:
* DELAY_IN_MS нужно оптимизировать, чтобы не перегружать сервис и найти правильный тур
* MAX_SEARCH_TIME тоже нужно подправить, т.к. это не гарантированное время


======

##### Идеи:
* Начинать поиск самых дешевых в NoSQL БД только после 10 секунд или по завершению поиска в БД

чтобы не нагружать сервис и в любом случае иметь возможность найти перелёты (т.к. максимальное время поиска 20с)

* Вынести всю логику из обработчиков сообщений SearchActor в private методы
* Поправить тесты - работают, но выглядят страшно)
