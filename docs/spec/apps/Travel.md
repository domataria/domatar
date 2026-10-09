# DOMATAR - TRAVEL APP

[IMPLEMENTED — Update-TravelApp.txt]

Travel searches flights, hotels, and sights, and books one short
trip as objects on the installing user's host. One app, one
install, one desktop tile named Travel. Searching and booking
are messages on that user's `app-travel` object.

The same facade serves the travel page and, later, the AI Agent.
This app does not change the agent.

Flights are Duffel Flights API v2 calls made with a test token.
Hotels and sights are Geoapify places: the names and streets are
real. Hotel nightly prices are a displayed euro amount this app
computes. They are not a live hotel rate, and they are not Duffel
Stays rates. Booking a hotel or a sight does not reserve anything
outside Domatar. Booking a flight creates a Duffel test order.
No card is charged.

Companion documents:

- [Writing Apps](Writing-Apps.md) — manifest, AppInstall, facade
- [LLM Messages](../platform/LLM-Messages.md) — the six tools
- [AI Agent](AIAgent.md) — later caller of this facade

The Java realisation is `apps/travel/`.


## PART 1 — IDENTITY

App id `travel`. Display name Travel. Home host `travel`. Home
user `travel@travel`, created when this provider offers host
`travel`. Per-user host `travel-<actId>`.

Travel is not a default app. In the two-provider workshop, host
`travel` is offered on prv2, with bookstore, spreadsheet, money,
and appstore. A prv1 user installs it there. It is not offered
on prv1. A single-provider deployment that lists `travel` in
`DOMATAR_OFFERED_HOSTS` creates the home host on that provider.

`installProvider` registers the catalog row and, on the provider
that offers host `travel`, the home host, the home user, and the
class and service descriptors. `installUser` creates the
sub-host, `app-travel`, and the trips container. Both are
idempotent.

After install, Navigator shows:

```
root → app-travel → trips → <trip leaves>
```

The search cache is not a child of `app-travel`. Navigator shows
trips, not leftover offers.

The installing user calls that user's `app-travel` object.
Another user's trips are not readable through this facade. The
facade does not draw credits. There is no Cost on these messages.
Prices travel inside search results and on the trip.


## PART 2 — WHAT IS REAL

2.1  Flights

A row is a Duffel offer: airline, origin, destination, departure,
arrival, price, currency, and cabin. The app does not rewrite
times or prices. Duffel Airways (IATA ZZ) is listed first when
it appears. The facade returns at most 8 offers. An empty offer
list is a successful search with `Offers` empty and `Total` 0.
No flight is invented.

The display Name is one line, unique in this user's current
flight cache. A second offer that would share the line gets
the suffix ` (2)`.

`TRAVEL_DUFFEL_TOKEN` must start with `duffel_test_`. Every
Duffel request sends `Authorization: Bearer`, `Duffel-Version: v2`,
and `Accept: application/json`. The app creates an offer request
and, on BookTrip, an order. It does not call Stays, Payments, or
order changes. A token that is set and does not start with
`duffel_test_` is refused with "Refusing live Duffel token", and
nothing is called. A missing token fails flight search and
BookTrip with "Travel not configured: TRAVEL_DUFFEL_TOKEN missing".

An offer with no Duffel expiry is treated as expired 30 minutes
after the search.

2.2  Hotels

A row is a Geoapify place in category `accommodation.hotel`:
name, street, and city. The display Name is the place name. A
second place with the same name gets the street appended with
an em dash. A further collision gets ` (2)`. That string is the
Name BookTrip accepts. A place with a blank street is dropped.

NightlyPrice is whole euros, stable for a Geoapify place id:

```
NightlyPrice = 95 + (U mod 386)
```

U is the unsigned 16-bit integer formed by the first two bytes
of SHA-256(PlaceId). The band is 95 through 480 inclusive. The
same hotel returns the same NightlyPrice on every search and
every page. TotalPrice is NightlyPrice times Nights. Currency
is EUR. The price is an attribute of the row. It is not charged.

2.3  Sights

Sights use `tourism.attraction` and `entertainment.museum`,
merged and deduplicated by Geoapify place id. Names and streets
are real. There is no price. A sight is not reserved. BookTrip
copies the chosen names onto the trip.

2.4  Paging and cities

SearchHotels and SearchSights return at most 12 rows. Page is
1-based. An omitted Page means 1. Total is the number of places
the app walked. The walk asks Geoapify for pages of 500 until a
short page comes back, and it stops after a safety limit so a
full page cannot loop forever.

Hotel and sight search geocode the city. An empty result is
"Unknown city: <name>". Flight search takes Origin and
Destination. A three-letter Latin code is an IATA airport
code. These names stay pinned, matched without case:

```
Paris      CDG
London     LHR
New York   JFK
```

Any other name is looked up with Duffel place suggestions.
When that city has an airport with the same code, that airport
is used. Otherwise the first international airport in the list
is used, and a seaplane base is skipped. A name Duffel does not
resolve is "Unknown city: <name>".

Depart, Return, and CheckIn are calendar dates `YYYY-MM-DD`.
Nights is a positive integer. A bad date is "Bad date". A bad
night count is "Bad nights".

`TRAVEL_GEOAPIFY_KEY` is the query key for geocoding and places.
A missing key fails hotel and sight search with "Travel not
configured: TRAVEL_GEOAPIFY_KEY missing". Keys live in the
process environment. They are not attributes, not source, and
not text sent to the model.


## PART 3 — MESSAGES

Messages are on class `(travel, app)`, object `app-travel`.
The service descriptor carries a class Description and, for
each message, Name, Description, SideEffect, and Parms.

Class Description:

```
Travel app. Search flights, hotels, and sights, then
book one trip. Call the searches first. BookTrip only
accepts Names returned by those searches. Do not invent
flight numbers, hotel names, or sight names.
```

3.1  SearchFlights (Read)

Parms: Origin, Destination, Depart, Return. All four required.

```
{ Total, Offers:[{ Name, Airline, Origin, Destination,
   DepartAt, ArriveAt, Price, Currency, Cabin }] }
```

Name is the handle BookTrip accepts. The response does not
include the Duffel offer id.

3.2  SearchHotels (Read)

Parms: City, CheckIn, Nights, Page. Page defaults to 1.

```
{ Total, Page, Hotels:[{ Name, Street, City,
   NightlyPrice, Currency, Nights, TotalPrice }] }
```

Currency is EUR. NightlyPrice is the displayed price in
PART 2.2, not a Duffel Stays rate. The response does not
include the Geoapify place id.

3.3  SearchSights (Read)

Parms: City, Page. Page defaults to 1.

```
{ Total, Page, Sights:[{ Name, Street, City, Category }] }
```

Sights are not reserved. Day1, Day2, and Day3 must be Names
from this list.

3.4  BookTrip (Write)

Parms: Title, FlightName, HotelName, Day1, Day2, Day3. All
six required. A day may name more than one sight, separated
by commas. Empty segments are skipped.

BookTrip resolves those Names in this user's search cache.
A miss is "Unknown flight; search again", "Unknown hotel;
search again", or "Unknown sight; search again". An expired
offer is "Offer expired; search again". A second BookTrip
with the same Title, matched case-insensitively, is "Trip
already exists: <Title>". Any of those stops before a Duffel
order is created.

On success the app creates a Duffel test order for the cached
offer, paid from the Duffel test balance for that offer's
amount and currency. The passenger name is the caller's
usrName when it is exactly two words. Otherwise the passenger
is "Demo Traveler". The remaining Duffel fields are fixed test
values and are not stored as the user's identity. A Duffel
refusal is "Duffel rejected the order: <reason>", and no trip
is written.

The trip is then written. If that write fails after the order
was created, the app cancels the test order and does not leave
a trip. The response is the GetTrip shape for the new trip.

3.5  GetTrips (Read)

No parms. Newest BookedAt first.

```
{ Trips:[{ Title, Destination, Depart, Return, HotelName }] }
```

3.6  GetTrip (Read)

Parm: Title. Match is case-insensitive. Unknown title is
"Unknown trip: <Title>". The attributes are:

```
Title, Origin, Destination, Depart, Return,
FlightName, Airline, FlightPrice, FlightCurrency,
DuffelOrderId, BookingReference,
HotelName, HotelStreet, CheckIn, Nights, NightlyPrice,
HotelTotal, HotelCurrency, Day1, Day2, Day3, BookedAt
```

HotelCurrency is EUR. Day1, Day2, and Day3 are the sight
Names. The trip does not store Duffel offer ids or Geoapify
place ids.

3.7  Cache

One object per user, class `(travel, cache)`, ObjId `cache`,
not linked from `app-travel`. It holds the latest flight
offers and the latest hotel and sight lists for this user,
including the server ids and the offer expiry. A new search
of one kind replaces only that kind.

3.8  Side effects

SearchFlights, SearchHotels, SearchSights, GetTrips, and
GetTrip are Read. BookTrip is the only Write.


## PART 4 — PAGE

The Travel tile opens `travel.html`. The page posts the six
facade messages to TravelWui. It searches flights, hotels, and
sights, pages hotels and sights, fills BookTrip from clicked
Names, lists trips, and opens one. While a search is in flight the section shows "Searching...".
For flights it sits beside Search flights. For hotels and
sights it sits to the right of Next. The previous rows of that
search are cleared. The text is removed when the new rows
arrive. The browser does not call Duffel or
Geoapify. Any other Action is "Unknown action" and is not
dispatched.


## PART 5 — NON-GOALS

- A live Duffel token, a card charge, Duffel Payments, or
  Duffel Stays.
- OpenTripMap. Geoapify is the places source for hotels and
  sights.
- Inventing flights, hotel names, or sight names when the
  upstream call returns nothing.
- A second tile, a second app id, or a second facade for the
  agent.
- Changing the AI Agent, its loop, or its confirmation card.
  BookTrip is Write so that loop can stop on it.
- Hecto, Money, or any Domatar payment. Displayed prices are
  attributes.
- Cars, restaurants, multi-city trips, or ticket sales for
  sights.
- A new third-party Java library. Outbound calls use
  `java.net.http.HttpClient`.
- Changing v1 actId / ownId math, or existing host names.
