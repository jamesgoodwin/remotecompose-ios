# Photographs used by the demos

All of them are Creative Commons Zero (CC0): released into the public domain, so no attribution
is required and there is no share-alike condition to propagate into this repository. They are
credited anyway — being able to say where a file came from is worth more than the licence
strictly demands.

A note on the request they answer: GPL is a software licence and is essentially never applied to
photographs. CC0 is the closest equivalent and the most permissive of the free image licences.

Found on Wikimedia Commons by filtering on the Wikidata statement `P275 = Q6938433` (licence =
CC0), then checking each one by eye. That filter is reliable about the licence and says nothing
about the subject: it first returned two eighteenth-century paintings, a religious artwork and a
photograph of a photographer.

## Carousel (page 15)

Cropped to the card's 250x168 aspect, resized to 360x242 and encoded as JPEG at quality 72,
which keeps all five inside about 75 kB.

| Card | Photographer | Licence | Source |
| --- | --- | --- | --- |
| Brecon | Tom Rickhuss tomrickhuss | CC0 | [Brecon Beacons field (Unsplash).jpg](https://commons.wikimedia.org/wiki/File:Brecon_Beacons_field_(Unsplash).jpg) |
| Snowdon | Paul Earle paulearlephotography | CC0 | [One morning in Snowdonia (Unsplash).jpg](https://commons.wikimedia.org/wiki/File:One_morning_in_Snowdonia_(Unsplash).jpg) |
| Dartmoor | Simon Cobb | CC0 | [Hunter's Tor (Castle Drogo).jpg](https://commons.wikimedia.org/wiki/File:Hunter%27s_Tor_(Castle_Drogo).jpg) |
| Cairngorm | Unsplash | CC0 | [Cairngorms-national-park-1209824.jpg](https://commons.wikimedia.org/wiki/File:Cairngorms-national-park-1209824.jpg) |
| Mourne | Michael Shannon mgshannon | CC0 | [Mourne Mountains, Newry, United Kingdom (Unsplash).jpg](https://commons.wikimedia.org/wiki/File:Mourne_Mountains,_Newry,_United_Kingdom_(Unsplash).jpg) |

## Parallax scene (page 14)

A parallax needs layers that can sit in front of each other, and a photograph is one flat plane.
The three ridges are therefore cut out of photographs of mountains against a bright sky: for each
column the skyline is the first pixel darker than halfway between the sky and the rock, that edge
is run through a median filter so a dark patch in the sky cannot turn a column into a stripe, and
everything above it is made transparent. Each ridge is then tinted towards the colour of the air
in proportion to its distance, which is what makes the far one read as far, and its lowest row is
carried down so that no hard bottom edge appears as it lags.

They are saved as 64-colour PNGs. A near-silhouette has few colours in it, so that costs nothing
visible and takes the three of them from 103 kB to under 3 kB.

| Layer | Photographer | Licence | Source |
| --- | --- | --- | --- |
| Sky | Jeremy Bishop tidesinourveins | CC0 | [Purple and pink cloudy sky (Unsplash).jpg](https://commons.wikimedia.org/wiki/File:Purple_and_pink_cloudy_sky_(Unsplash).jpg) |
| Far ridge | Pacific Austin _pacifist | CC0 | [Silhouette mountain range and yellow sky (Unsplash).jpg](https://commons.wikimedia.org/wiki/File:Silhouette_mountain_range_and_yellow_sky_(Unsplash).jpg) |
| Middle ridge | Blake Richard Verdoorn blakeverdoorn | CC0 | [Sunset over mountain silhouettes (Unsplash).jpg](https://commons.wikimedia.org/wiki/File:Sunset_over_mountain_silhouettes_(Unsplash).jpg) |
| Near ridge | arvin febry arvinfebry | CC0 | [Mountain silhouette at dusk (Unsplash).jpg](https://commons.wikimedia.org/wiki/File:Mountain_silhouette_at_dusk_(Unsplash).jpg) |
