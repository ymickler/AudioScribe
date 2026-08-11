# AudioScribe – Optimierungen

Bestandsaufnahme vom 11.08.2026 (nach Einführung der Live-Karten für laufende Transkriptionen),
Umsetzung am selben Tag. Status je Punkt unten.

**Zusammenfassung: 12 von 13 Punkten umgesetzt.** Punkt 13 (Queue-Repository) ist bewusst offen –
Begründung dort.

---

## 🍏 Low-hanging fruits

### 1. Hartkodierte englische Status-Texte lokalisieren — ✅ erledigt
`TranscriptionOverlayService` setzte `item.status` an vielen Stellen als festen englischen String
und löste an anderen dasselbe per `if (uiLanguage == "de")` inline. Durch die neue Verlaufskarte
war dieser Text dauerhaft sichtbar. Jetzt: alle Texte über `Localization`, dazu
`Localization.getString(key, lang, vararg args)` mit `{1}`/`{2}`-Platzhaltern, damit Zähler
innerhalb des übersetzten Satzes bleiben. Auch die Overlay-Buttons (Copy/Share/Cancel) und der
Zwischenablage-Toast waren noch englisch – ebenfalls erledigt.

### 2. Toter DAO-Code / doppelte Suche — ✅ erledigt
`searchTranscriptions()` entfernt; an seiner Stelle steht ein Kommentar, warum es **keine**
SQL-Suche gibt (bei aktiver Verschlüsselung stünde dort Chiffrat, ein `LIKE` würde stumm nichts
finden). Gesucht wird weiterhin im Repository auf der entschlüsselten Liste.

### 3. Ungenutztes Feld `duration` — ✅ erledigt
Wird bei Abschluss mit der tatsächlichen Transkriptionsdauer geschrieben und auf der Verlaufskarte
neben dem Datum angezeigt. Die Formatierung liegt jetzt in `data/DurationFormat.kt` und wird von
Overlay, Benachrichtigung und Karte gemeinsam genutzt.

### 4. `printStackTrace()` → `android.util.Log` — ✅ erledigt
Alle 10 Stellen ersetzt, jeweils mit aussagekräftiger Meldung und Tag (`Log.e` für echte Fehler,
`Log.w` für tolerierbare). Krypto-Fehler sind damit im Release-Build überhaupt erst auffindbar.

### 5. Room-Index auf `timestamp` — ✅ erledigt
`@Entity(indices = [Index("timestamp")])` plus `MIGRATION_2_3` (Schema v3).

### 6. `fallbackToDestructiveMigration()` entfernen — ✅ erledigt
Entfernt. Eine vergessene Migration crasht jetzt in der Entwicklung, statt still den kompletten
Verlauf des Nutzers zu löschen.

---

## 🍋 Mittlerer Aufwand

### 7. Freilaufende Coroutine-Scopes bündeln — ✅ erledigt
Die 11 einzelnen `CoroutineScope(Dispatchers.IO/Main)` sind durch einen `serviceScope`
(`SupervisorJob + Dispatchers.IO`) ersetzt, der in `onDestroy()` gecancelt wird. Zwei Blöcke
laufen bewusst unter `NonCancellable`: das Persistieren einer **fertigen** Transkription (sonst
ginge das Ergebnis beim gleichzeitigen Herunterfahren verloren) und der Cancel-Aufruf an den
Server (der beendet dort die CPU-Last).

### 8. Entschlüsselung cachen — ✅ erledigt
`CryptoHelper.decrypt` memoisiert über eine LRU (200 Einträge, `LinkedHashMap` mit
Zugriffs-Reihenfolge). Chiffrat ist unveränderlich, der Cache-Key ist der gespeicherte Wert.
Damit entfällt das erneute Entschlüsseln der gesamten Liste bei jedem Fortschritts-Tick.

### 9. `MainActivity.kt` aufteilen — ✅ erledigt
1807 Zeilen → `MainActivity.kt` (94), `MainViewModel.kt` (127), `MainScreen.kt` (1540),
`HistoryItemCard.kt` (~300). Reiner Datei-Split ohne Logikänderung.

### 10. OkHttp-Client wiederverwenden — ✅ erledigt
Der Default-Parameter zeigt jetzt auf einen prozessweiten `sharedClient`. Die per-Aufruf
unterschiedlichen Timeouts entstehen weiterhin über `newBuilder()` – der geteilte Connection- und
Thread-Pool bleibt dabei erhalten, genau so ist OkHttp gedacht. Der Test-Injektionspunkt bleibt.

---

## 🍈 Größer

### 11. Testsuite entpollen — ✅ erledigt (soweit sinnvoll möglich)
Die vier handgeschriebenen 6-Sekunden-Polling-Schleifen sind durch einen gemeinsamen
`awaitHistory(description) { … }`-Helfer ersetzt: kehrt sofort zurück, sobald die Bedingung gilt,
und schlägt mit einer lesbaren Meldung statt `expected:<1> but was:<2>` fehl.
**Bewusst nicht** auf virtuelle Zeit umgestellt: der Service läuft auf echtem `Dispatchers.IO` und
die Engines starten eigene Threads – vollständig deterministisch würde das erst mit injizierbaren
Dispatchern durch Service *und* Engines, was deutlich invasiver wäre als der Nutzen rechtfertigt.

### 12. Doppelte Status-Darstellung Overlay ↔ Benachrichtigung ↔ Karte — ✅ erledigt
Zwei gemeinsame Ableitungen im Service (`statusLabel(item)` für den Zustand, `progressLabel(...)`
für die laufende Phase) versorgen jetzt alle drei Oberflächen. Die Benachrichtigung zeigt dadurch
zusätzlich *was* gerade passiert statt nur „x von y (42%)".

### 13. Zustand aus dem Service in ein Repository ziehen — ❌ bewusst offen
Der Vorschlag war ein `TranscriptionQueueRepository` als Single Source of Truth, auf das Service
und App gleichermaßen zugreifen.

**Nicht umgesetzt, weil:** der Nutzen wäre aktuell null und das Risiko hoch. Das eigentliche
Anwenderproblem („laufende Transkription in der App sehen") ist seit v1.23.0 über die Datenbank
gelöst und funktioniert. Ein Queue-Repository würde diesen Weg ersetzen – also die gerade
ausgelieferten `status`/`statusText`/`progress`-Spalten samt Migration wieder zurückbauen – und
dabei Queue-Verwaltung, Overlay-Binding und Benachrichtigungslogik eines 1300-Zeilen-Service
gleichzeitig anfassen. Das ist ein eigenes Vorhaben mit eigenem Testlauf, kein Aufräumpunkt, den
man im selben Durchgang mitnimmt.

**Wann es sich lohnt:** sobald die App tatsächlich in die laufende Verarbeitung eingreifen soll –
allen voran „Transkription aus der App heraus abbrechen", was heute nur aus Overlay/
Benachrichtigung geht. Dann ist der Umbau begründet und sollte als eigener Schritt erfolgen.
