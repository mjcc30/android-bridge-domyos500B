# Protocole propriétaire du Domyos 500B (ISSC / « changyow »)

Le Domyos 500B n'expose pas toujours le service FTMS standard `0x1826` en GATT : il
parle un protocole propriétaire binaire sur le service du module UART BLE
Microchip/ISSC (préfixe `49535343` = ASCII « ISC »).

| Rôle | UUID |
|---|---|
| Service | `49535343-fe7d-4ae5-8fa9-9fafd205e455` |
| Notify (rameur → téléphone) | `49535343-1e4d-4bd9-ba61-23c647249616` |
| Write (téléphone → rameur) | `49535343-8841-43f4-a8d4-ecbe34729bb3` |

Tous les champs multi-octets des paquets `f0 bc` sont **big-endian**. Les commandes
et leurs réponses commencent par `0xF0` et finissent par un **checksum = somme de tous
les octets précédents & 0xFF**.

Références : [qdomyos-zwift](https://github.com/cagnulein/qdomyos-zwift)
(`src/devices/domyosrower/domyosrower.cpp`, notamment `btinit_changyow` et
`updateDisplay`), [4lberto/rowing-at-home](https://github.com/4lberto/rowing-at-home),
[Palantir555/domyos-el500-hack](https://github.com/Palantir555/domyos-el500-hack).

## 1. Paquets reçus (notifications) : `f0 bc …` — 26 octets

Exemple réel capturé (26 coups, pace 274 s/500m, 23 spm, 4 kcal, 1, 84 bpm) :

```
F0 BC 00 1A 41 D6 01 12 00 17 00 04 00 01 01 00 01 00 54 00 13 00 00 00 01 76
```

| Octets | Taille | Champ | Notes |
|---|---|---|---|
| 0 | 1 | Header | toujours `0xF0` |
| 1 | 1 | Commande | `0xBC` = données |
| 2-3 | u16 BE | **Nombre de coups** | |
| 4-5 | u16 BE | ? (souvent résidu de distance/temps) | ignoré |
| 6-7 | u16 BE | **Pace** en s/500m | 0 = à l'arrêt |
| 8 | 1 | ? | ignoré |
| 9 | 1 | **Cadence** (coups/min) | |
| 10-11 | u16 BE | **Calories** (kcal) | |
| 12-13 | u16 BE | **Distance** (unité 0,1 km, ex. `00 02` = 0,2 km) | compteur interne lent |
| 14 | 1 | Résistance (1-15) | |
| 15-17 | 3 | ? | ignorés |
| 18 | 1 | **Fréquence cardiaque** (bpm) | 0 = pas de ceinture |
| 19-20 | 2 | ? | ignorés |
| 21 | 1 | Inclinaison (0-15) | |
| 22 | 1 | Événement bouton | `0x06` = inclinaison+, `0x07` = inclinaison- |
| 23-24 | 2 | ? | ignorés |
| 25 | 1 | Checksum | somme des octets 0-24 & 0xFF |

> Le rameur **n'envoie pas la puissance** : le pont la recalcule depuis le pace avec la
> formule Concept2/FTMS `W = 2.80 / (pace/500)^3` (ex. pace 158 s → 89 W, pace 274 s → 17 W).

## 2. Commandes envoyées (write) : init + keepalive

Séquence d'init « changyow » COMPLÈTE à chaque connexion (cf. `btinit_changyow`,
octets copiés tels quels — les 4 longues trames sont écrites en 2 paquets 20+rest) :

```
F0 C8 01 B9                                                  (4 o)  set speed/incline à 0
F0 C9 B9                                                     (3 o)
F0 A3 93 / F0 A4 94 / F0 A5 95 / F0 AB 9B / F0 C4 03 B7       (3-4 o) séquence principale
F0 AD FF×18 01 FF FF 8B                                      (23 o) init résistance + icône BT
F0 CB 02 00 08 FF×13 01 00 00 01 FF FF FF FF B6               (27 o) mode « affichage piloté par l'app »
F0 AD FF FF 00 05 FF×7 00 00 FF FF FF 01 FF FF FF 94          (23 o) init résistance 2
F0 CB FF×13 01 00 14 01 FF×8 BD                              (27 o) « start tape »
```

Keepalive à **1 Hz** tant que la session est active (sinon le rameur coupe) :

```
F0 AC 9C
```

Arrêt « stop tape » (non utilisé pour l'instant) :

```
F0 C8 00 B8
```

## 3. Trames d'affichage vers l'écran du rameur : `f0 cd` et `f0 cb`

Quand un central BLE est connecté, la console du 500B **attend que l'app lui renvoie
les valeurs à afficher** (c'est ce que fait Kinomap, et `domyosrower::updateDisplay`
dans qdomyos-zwift). Sans ces trames, l'écran n'affiche pas les données de la séance.

Deux trames de **27 octets**, envoyées à ~1 Hz, chacune écrite en **2 paquets
(20 + 7 octets)** pour tenir dans un MTU 23 si le module ne négocie pas plus :

### 3.1 `f0 cd` — distance

| Octets | Valeur |
|---|---|
| 0-2 | `F0 CD 01` |
| 3-4 | distance u16 BE, **unité 0,1 km** (`odometer*10` chez qdomyos ; le pont envoie sa distance intégrée locale / 10) |
| 5 | `01` |
| 6-25 | `FF` × 20 |
| 26 | checksum |

Exemple émis par le pont (distance intégrée 10820 m → 1082 unités = `0x043A`) :

```
F0 CD 01 04 3A 01 FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF   (20 octets)
FF FF FF FF FF FF E9                                            (7 octets, E9 = checksum)
```

### 3.2 `f0 cb` — mapping **confirmé** du firmware 500B (photos de la console en mode BT)

Le firmware du 500B **ne dispose pas les champs comme le changyow de qdomyos-zwift**
(vitesse en 7-8, FC en 12, kcal en 19-20). Mapping constaté empiriquement sur machine
réelle (photos 27/11/2026 + trames « Test écran » : octet i = 10+i, le champ Spm
affichait « 370 » pour 7-8 = `0x1112` = 4370, tronqué à 3 chiffres) :

| Octets | Champ console | Codage observé |
|---|---|---|
| 0-2 | `F0 CB 03` | — |
| 3 | **Time** minutes | affiché en `%04d` de `min*60+sec` (11 min 44 s → « 0704 ») |
| 4 | **Time** secondes | idem |
| 5 | `FF` | — |
| 6 | `01` | — |
| 7-8 | **Spm** | u16 BE, 3 chiffres (mod 1000) |
| 9-11 | `02 01 00` | — |
| 12-13 | **Time/500M** | u16 **LE**, affiché `M:SS` avec min = v/100, sec = v%100 (81 → « 0:81 », 100 → « 1:00 ») : on encode le pace en `min*100+sec` (3:40 → 340) |
| 14 | `01` | affiché « 01 » en bas de l'écran (n° de programme, pas la résistance) |
| 15 | **Kcal** | 1 octet (capé à 255) |
| 16 | cadence spm | champ non visible sur le 500B en mode BT (icône BT au milieu) |
| 17-18 | `01 01` | — |
| 19-20 | **Km** | affichage `hi.lo` : octet 19 = partie entière km, octet 20 = centièmes (12 → « 0.12 », 2 → « 00.2 » ; quirk : 0,05 km s'affiche « 0.5 ») |
| 21-25 | `01 FF FF FF FF` | — |
| 26 | checksum | somme des 26 premiers octets & 0xFF |

Le champ **Count** (coups) et le « 01 » de programme sont gérés par la console
elle-même ; la **FC n'est pas affichée** en mode BT (champ remplacé par l'icône BT).

Exemple émis par le pont (3 s, 27 spm, pace 274 s = 4:34, 4 kcal, 100 m) :

```
F0 CB 03 00 03 FF 01 00 1B 02 01 00 B2 01 01 04 1B 01 01 00   (20 octets)
0A 01 FF FF FF FF BB                                            (7 octets, BB = checksum)
```

> Le bouton **« Test écran »** de l'app envoie des trames marqueurs (octet i = 10+i)
> pour re-vérifier ce mapping octet par octet sur une photo de la console.

## 4. Autres commandes

| Commande | Fonction | Utilisée par le pont |
|---|---|---|
| `F0 AD …` (24 octets) | **résistance forcée** (`forceResistance` qdomyos-zwift, octet 10 = niveau 1-15, checksum accumulé in situ depuis 0xFF) | oui — boutons app + relais FTMS 0x04 |
| `F0 E3 <n+1> <cksum>` | inclinaison forcée (`forceInclination`) | non |
| `F0 CC FF FF FF FF 01 FF B8` | variante d'init « telink » (MAC commençant par `57`) | non (500B = changyow) |
| `F0 C8 00 B8` | « stop tape » | non (réservé arrêt de session) |

## 5. Côté FTMS exposé au central (MyWhoosh)

Le pont ré-émet les métriques en FTMS standard `0x1826` sous le nom **« PM5 »** :

- `2ACC` Fitness Machine Feature = `26 01 00 08`
- `2AD1` Rower Data, **notify 4 Hz**, trame normalisée **16 octets** (MyWhoosh exige
  ≥ 16 octets), flags `0x003E` (little-endian) :
  `flags(2) | strokeRate(1, ×2) | strokeCount(2 LE) | distance(3 LE, mètres intégrés) | pace(2 LE) | power(2 LE, sint16) | padding(4)`
- `2AD9` Control Point, write + indicate : toute écriture reçoit `80 <op> 01` ;
  0x00 (Request Control) et 0x07 (Start/Resume) sont relayés vers le rameur.
- `2A37` Heart Rate Measurement (service `0x180D`), notify 4 Hz : `00 <bpm>`.
