# AudioScribe – Optimierungspotenzial

Bestandsaufnahme vom 11.08.2026, nach Einführung der Live-Karten für laufende Transkriptionen.
**Nichts davon ist umgesetzt** – die Liste ist reine Dokumentation, sortiert nach Aufwand/Nutzen
(oben: low-hanging fruits).

---

## 🍏 Low-hanging fruits (je < 1 h, geringes Risiko)

### 1. Hartkodierte englische Status-Texte lokalisieren
`TranscriptionOverlayService` setzt `item.status` an vielen Stellen als festen englischen String
(`"Waiting..."` Z. 96, `"Transcribing via server... (x%)"` Z. 409/581, `"Loading Whisper model..."`
Z. 584-588, `"Completed"` Z. 453, `"Error occurred"` Z. 617), während direkt daneben
`Localization.getString(...)` genutzt wird. Zwei Stellen (Z. 969, 1064) lösen das per
`if (uiLanguage == "de")` inline – dritte Variante desselben Problems.
Neu relevant: dieser Text landet jetzt als `statusText` auf der Verlaufskarte, ist also für
deutschsprachige Nutzer dauerhaft sichtbar. Fix: Keys in `Localization.kt` ergänzen und die
Literale ersetzen.

### 2. Toter DAO-Code / doppelte Suche
`TranscriptionDao.searchTranscriptions()` wird nirgends aufgerufen – `TranscriptionRepository.search()`
filtert stattdessen im Speicher über die bereits entschlüsselte Liste. Das ist bei aktiver
Verschlüsselung sogar korrekt (SQL kann Chiffrat nicht durchsuchen), aber die ungenutzte Query
sollte weg, sonst nutzt sie irgendwann jemand und die Suche findet stumm nichts mehr.

### 3. Ungenutztes Feld `TranscriptionEntity.duration`
Wird nie geschrieben und nie gelesen (die tatsächliche Dauer lebt als `durationLabel` nur im
Service). Entweder beim Abschluss mitschreiben und auf der Karte anzeigen, oder in der nächsten
Migration entfernen.

### 4. `printStackTrace()` durch `android.util.Log` ersetzen
10 Stellen (`CryptoHelper` 3, `TranscriptionOverlayService` 6, `WhisperEngineImpl` 1). In
Release-Builds landet das im Nichts bzw. ohne Tag – Fehler bei Ver-/Entschlüsselung wären
dadurch praktisch nicht diagnostizierbar. Der Service nutzt an anderer Stelle bereits `Log.w`.

### 5. Room-Index auf `timestamp`
Jede Abfrage ist `ORDER BY timestamp DESC`, es gibt keinen Index. Bei wachsendem Verlauf sortiert
SQLite jedes Mal komplett. Ein `@Entity(indices = [Index("timestamp")])` kostet eine Zeile plus
Migration.

### 6. `fallbackToDestructiveMigration()` entfernen
Seit v2 existiert eine echte Migration. Der Fallback steht weiter daneben und würde bei der
nächsten vergessenen Migration den kompletten Verlauf des Nutzers wortlos löschen – genau das,
wogegen die Migration geschrieben wurde.

---

## 🍋 Mittlerer Aufwand (1–4 h)

### 7. Freilaufende Coroutine-Scopes im Service bündeln
`TranscriptionOverlayService` erzeugt an 11 Stellen `CoroutineScope(Dispatchers.IO/Main).launch {}`.
Diese Scopes hängen an keinem Lifecycle: bei `stopSelf()`/`onDestroy()` laufen sie weiter, halten
den Service-Context (Memory-Leak) und schreiben ggf. noch in die DB. Ein einziger
`serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`, in `onDestroy()` gecancelt,
ersetzt alle – die laufenden Transkriptions-Jobs bleiben davon unberührt, weil die separat
verwaltet werden.

### 8. Entschlüsselung cachen
`TranscriptionRepository.allTranscriptions` entschlüsselt bei **jeder** Emission die komplette
Liste neu – also auch bei jedem Fortschritts-Tick einer laufenden Transkription (1×/Sekunde). Bei
200 Einträgen sind das 200 AES-Operationen pro Sekunde auf dem Main-geteilten Flow. Ein kleiner
LRU-Cache `id+hash -> Klartext` oder Entschlüsselung erst beim Rendern der sichtbaren Karte
räumt das ab.

### 9. `MainActivity.kt` aufteilen (1807 Zeilen)
History-Screen, Settings-Screen, `HistoryItemCard`, ViewModel und Activity stecken in einer Datei.
Reine Datei-Splits ohne Logikänderung: `ui/HistoryScreen.kt`, `ui/SettingsScreen.kt`,
`ui/HistoryItemCard.kt`, `MainViewModel.kt`.

### 10. OkHttp-Client wiederverwenden
`ServerTranscriptionEngine` legt per Default für jede Instanz einen neuen `OkHttpClient` an, und
der Service erzeugt pro Versuch eine neue Engine. Jeder Client bringt eigenen Connection-Pool und
Thread-Pool mit – ein `companion object`-Singleton (Timeouts weiterhin pro Aufruf über
`newBuilder()`) spart Sockets und Aufwärmzeit bei jeder Server-Transkription.

---

## 🍈 Größer (> 4 h, eher Refactoring-Projekte)

### 11. Testsuite entpollen
Die Robolectric-Tests warten in `while (System.currentTimeMillis() - start < 6000)`-Schleifen mit
`delay(100)` auf DB-Zustände. Das ist wallclock-abhängig (auf langsamen CI-Runnern flaky) und
kostet pro Test bis zu 6 s. Mit `runTest` + virtueller Zeit bzw. `Turbine` auf dem Flow wäre das
deterministisch und praktisch instantan.

### 12. Doppelte Status-Darstellung Overlay ↔ Benachrichtigung ↔ Verlaufskarte
Es gibt inzwischen drei Oberflächen, die denselben Zustand aus derselben `QueueItem` ableiten,
jede mit eigener Formatierungslogik im Service (Overlay-Composable, `updateOverallProgressNotification`,
`syncItemToDatabase`). Eine gemeinsame `TranscriptionStatus`-Ableitung (ein Objekt → Label,
Fortschritt, Badge) würde ~150 Zeilen Duplikat im Service ersetzen und Divergenzen wie in Punkt 1
strukturell verhindern.

### 13. Zustand aus dem Service in ein Repository ziehen
Aktuell ist `TranscriptionOverlayService` gleichzeitig Foreground-Service, Queue-Manager,
Compose-Host und Persistenz-Schreiber (1300+ Zeilen). Ein `TranscriptionQueueRepository` als
Single Source of Truth (Flow von Queue-Items), auf den Service **und** App gleichermaßen
zugreifen, würde u. a. das jetzt über die Datenbank gelöste Live-Update überflüssig machen und
Abbrechen aus der App heraus ermöglichen.
