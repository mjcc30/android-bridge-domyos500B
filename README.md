# Domyos 500B Bridge — pont BLE vers MyWhoosh (FTMS)

Application Android (Kotlin / Jetpack Compose) qui fait office de **pont BLE autonome**
entre un rameur **Decathlon Domyos 500B** et l'application **MyWhoosh** (Android ou
MyWhoosh HD sur Windows) :

```
Domyos 500B ──BLE propriétaire "changyow"──> [ ce téléphone, app bridge ] ──BLE FTMS 0x1826, nom "PM5"──> MyWhoosh
        (service 49535343-…, paquets f0 bc)      GATT client + serveur + advertising          (2AD1 notify 4 Hz + 2A37 HR)
```

Le téléphone se comporte comme un **vrai rameur FTMS** vu de MyWhoosh : le bateau
avance dans le jeu, la puissance / cadence / pace / FC sont affichées, et la console
du 500B affiche les données de la séance (comme quand on connecte Kinomap).

## Prérequis

- Téléphone Android 13+ (testé sur Pixel 6 Pro, Android 16 / SDK 36). `minSdk = 33`
  (nouvelles signatures GATT avec paramètre `value`).
- Rameur Domyos 500B en mode Bluetooth (logo BT sur la console).
- MyWhoosh 6.x installé sur le téléphone (ou MyWhoosh HD sur PC Windows, même réseau/Bluetooth).

## Installation

1. Télécharger le dernier APK « nightly » :
   https://github.com/mjcc30/android-bridge-domyos500B/releases/download/nightly/app-debug.apk
   (ou scanner le QR code `qr-code.png` de la release).
2. Autoriser le navigateur à installer des applications inconnues, installer l'APK.
3. Dans l'app : accorder **Appareils à proximité** (Bluetooth) + la notification, puis
   passer **« Batterie sans restriction»** (bouton dans l'app) pour éviter que le
   service foreground ne soit tué.
4. **« Démarrer le bridge »**.

### Mise à jour

Bouton **« Mise à jour APK »** dans l'app : télécharge et installe le nightly par-dessus.
Si Android répond « App not installed » : désinstaller l'ancien APK, réinstaller via le
QR de la release nightly, puis réaccorder « Appareils à proximité ».
(La clé de signature debug est stable et embarquée dans le repo, donc la mise à jour
in-app fonctionne ; une clé régénérée à chaque build CI la casserait.)

## Utilisation

1. Allumer le rameur, attendre le **logo BT** sur sa console.
2. Dans l'app bridge : « Démarrer le bridge » → attendre les **4 pastilles vertes**
   (`Service`, `Advertising`, `Domyos`, `MyWhoosh` — cette dernière passe au vert
   quand MyWhoosh s'est abonné aux notifications).
3. Dans MyWhoosh : *Device Connection* → *Row* → appairer **PM5** ; carte *Heart Rate*
   → appairer **PM5** aussi (la FC vient du service 0x180D/2A37).
4. Lancer un workout dans MyWhoosh et ramer : le bateau avance, les données s'affichent
   dans MyWhoosh **et** sur l'écran du rameur.

### Logs

Bouton **« Envoyer les logs »** : partage la console interne (trames hex TX/RX, parse,
événements GATT). Les fichiers exportés (`bridge-logs*.txt`) ne sont **pas** commités.

## Ce que fait le pont

- **Côté rameur (GATT client)** : scan des services `0x1826` et `49535343-…`, connexion
  `TRANSPORT_LE`, MTU 247 demandé. Si le rameur n'expose pas FTMS (cas du 500B) :
  protocole propriétaire — init `f0 c8 01 b9` / `f0 c9 b9` / `f0 a3 93`, keepalive
  `f0 ac 9c` à 1 Hz, réception des paquets `f0 bc …` (26 octets, voir
  [docs/protocole-domyos.md](docs/protocole-domyos.md)).
- **Métriques** : spm, coups, pace, distance, FC parsés ; la **puissance est recalculée**
  depuis le pace (formule Concept2 `W = 2.80 / (pace/500)^3`) car le 500B ne l'envoie pas.
- **Distance intégrée localement** : le compteur distance du Domyos progresse trop
  lentement (1-2 après 37 coups) et MyWhoosh l'utilise pour la position/vitesse du bateau
  → le pont intègre lui-même `v = 500 / pace` entre deux paquets (reset quand le compteur
  de coups retombe) et envoie cette distance dans le champ *Total Distance* de 2AD1.
- **Côté MyWhoosh (GATT serveur + advertising)** : service FTMS `0x1826` complet
  (2ACC / 2AD1 / 2AD3 / 2ADA / 2AD9 + CCCD) exposé sous le nom **« PM5 »** (nom du moniteur
  Concept2 reconnu nativement par MyWhoosh), trame 2AD1 normalisée (16 octets, flags
  `0x003E`) notifiée à **4 Hz**, service Heart Rate `0x180D`/2A37 pour la FC.
  Toute écriture sur le Control Point 2AD9 est validée (indication `80 <op> 01`) et
  les opcodes 0x00 / 0x07 sont relayés vers le rameur.
- **Écran du rameur** : à 1 Hz, le pont renvoie au 500B ses propres métriques via les
  trames d'affichage `f0 cd` (distance) et `f0 cb` (temps, vitesse, FC, cadence, kcal),
  comme Kinomap / qdomyos-zwift — sinon la console n'affiche rien une fois connectée.

## Deux bugs framework Android contournés

- **Deux `addService()` back-to-back plantent le stub binder** du framework
  (`IndexOutOfBoundsException` dans `onServiceAdded`) et corrompent la table de handles :
  chaque écriture de CCCD est alors rejetée (« no desc for handle ») et MyWhoosh ne
  s'abonne jamais (0 W / 0 spm, pastille rouge). → Le service HR `0x180D` est ajouté
  **chaîné dans `onServiceAdded(FTMS)`**, une fois le premier confirmé.
- **MyWhoosh exige ≥ 16 octets** dans 2AD1 (`str.length() >= 31` caractères hex) :
  une trame de 12 octets est ignorée silencieusement. → Trame normalisée de 16 octets.

## Build local

```bash
JAVA_HOME=<jdk-17> ANDROID_HOME=<android-sdk> ./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest   # tests unitaires (parsers, trames, checksums)
```

## CI / releases

[.github/workflows/build-apk.yml](.github/workflows/build-apk.yml) : à chaque push sur
`main`, build de l'APK debug + **tests unitaires** + publication dans la release
roulante **nightly** (APK + QR code). Sur un tag `v*`, même build publié dans une
release versionnée avec QR code.

## Dépannage

| Symptôme | Cause probable | Action |
|---|---|---|
| Pastille `Domyos` rouge | Rameur éteint / pas en mode BT / hors portée | Logo BT sur la console, rester à < 2 m |
| Pastille `MyWhoosh` rouge, 0 W dans le jeu | MyWhoosh pas abonné (CCCD non écrit) | Voir logs : `CCCD 0x2AD1 ENABLE` doit apparaître ; re-appairer PM5 |
| « App not installed » à la mise à jour | Signature différente (vieille install) | Désinstaller + réinstaller via le QR nightly |
| Le bateau n'avance pas dans MyWhoosh | Distance 2AD1 à 0 | Corrigé (distance intégrée) — mettre à jour le nightly |
| L'écran du rameur n'affiche rien | Trames `f0 cb`/`f0 cd` absentes des logs | Mettre à jour le nightly (fonctionnalité récente) |
| MyWhoosh HD (Windows) ne voit pas PM5 | Scan passif sans nom, ou autre appli BLE ouverte | Le nom est dans le paquet principal ; fermer Bluetooth LE Explorer |

## Limitations connues

- Pont unidirectionnel pour le contrôle : seuls les opcodes 0x00/0x07 du Control Point
  sont relayés vers le rameur (pas de réglage de résistance/cible depuis MyWhoosh).
- La puissance affichée est **recalculée depuis le pace** (le 500B ne la mesure pas en
  BLE propriétaire).
- La distance envoyée à MyWhoosh est l'intégration locale (plus fidèle que le compteur
  du rameur) ; l'écran du rameur, lui, reçoit la valeur brute du rameur.

## Protocole

Voir [docs/protocole-domyos.md](docs/protocole-domyos.md) : détail octet par octet du
protocole propriétaire ISSC/changyow du Domyos 500B (réception `f0 bc`, commandes
init/keepalive, trames d'affichage `f0 cb` / `f0 cd`, checksum), avec exemples réels
capturés, et références (qdomyos-zwift, 4lberto/rowing-at-home).
