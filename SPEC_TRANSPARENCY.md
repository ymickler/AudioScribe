# Spezifikation: Transparenz & Performance der Server-Transkription

Erstellt am 13.08.2026. Grundlage ist eine Code-Analyse von AudioScribe (App) und
`audioscribe-server` (Gateway) nach zwei konkreten Nutzerbeschwerden:

1. **„Ich sehe nicht, ob die Verbindung zum Server geklappt hat."**
2. **„Der erste Prozentwert ist 30 und ändert sich ewig nicht."**

Der Fortschritt dieser Spezifikation wird in [PROGRESS.md](PROGRESS.md) gepflegt.
Jede Teilaufgabe unten zählt gleich viel (1 Punkt); der Prozentwert in `PROGRESS.md`
ist exakt `erledigte Kästchen / alle Kästchen`.

---

## 0. Problemanalyse (Ist-Zustand)

### 0.1 Warum die Anzeige bei 30 % einfriert

`engine/ServerTranscriptionEngine.kt` meldet auf dem gesamten Server-Pfad genau drei
Fortschrittswerte:

| Stelle | Wert | Zeitpunkt |
|---|---|---|
| `transcribe()` Eintritt | `0.10` | vor dem Bauen des Multipart-Bodys |
| vor `executeAsync(...)` | `0.30` | vor dem HTTP-Call |
| nach `executeAsync(...)` | `0.85` | wenn die **komplette** Antwort da ist |

Zwischen `0.30` und `0.85` liegen **Upload und die gesamte Transkription** als ein
einziger blockierender Call — bis zu 240 s (`timeoutMs = 240_000`). Der Wert *kann*
sich in dieser Zeit nicht bewegen. Ein statischer Prozentwert ist dabei schlimmer als
gar keiner: er ist von einem Absturz nicht unterscheidbar.

### 0.2 Warum der Serverstatus unsichtbar ist

* `TranscriptionOverlayService.updateForegroundNotification()` sucht das aktive Item mit
  `transcriptionQueue.find { it.activeJob != null }`. Auf dem Server-Pfad läuft die Arbeit
  aber unter `serverAttemptJob`; `activeJob` bleibt durchgehend `null`.
  → Das Badge `(Server)` / `(Lokal-Fallback: nicht erreichbar)` erscheint in der
  Benachrichtigung **nie**. Es ist ein reiner Filter-Bug, kein fehlendes Feature.
* Die Status-Texte `status_connecting_server` / `status_connected_server` existieren und
  werden gesetzt (`attemptServerEagerly`), sind aber nach jeweils 400 ms wieder überschrieben
  und danach dauerhaft weg. Nach dem Start gibt es kein Signal mehr, *dass* der Server
  überhaupt beteiligt ist.

### 0.3 Randbefunde aus derselben Analyse

* `attemptServerEagerly` wiederholt über `for (attempt in 1..2)` **jeden** Fehler — auch
  nicht wiederholbare (HTTP 400 „invalid model"). Worst Case: 2 × 240 s = 8 min Wartezeit,
  bevor überhaupt lokal weitergemacht wird.
* `updateForegroundNotification()` baut bei **jedem** Fortschritts-Tick zwei `PendingIntent`s
  und einen kompletten `NotificationCompat.Builder` neu und ruft `startForeground()` erneut auf.
* Das Gateway liefert in der Antwort bereits `durationMs` (`src/app.mjs`), die App verwirft
  diesen Wert ungenutzt.
* Timeout-Kaskade ist flach: App `240_000` = Gateway `DEFAULT_TIMEOUT_MS 240000` = Upstream.
  Die App kann aufgeben, während das Gateway gerade antwortet.

---

## 1. Badge-Bug in der Benachrichtigung beheben

**Priorität: 1 (höchster Effekt pro Aufwand) · Aufwand: sehr klein · Bereich: App**

**Problem:** Siehe 0.2 — das Server-/Fallback-Badge ist in Benachrichtigungsmodus unsichtbar.

**Lösung:** Die Suche nach dem aktiven Item in `updateForegroundNotification()` muss dieselbe
Bedingung verwenden wie `updateOverallProgressNotification()` (das den Bug bereits kennt und
korrekt `activeJob != null || serverAttemptJob != null` prüft).

**Akzeptanzkriterien:**
* Bei aktiver Server-Transkription steht im Benachrichtigungstitel `Transkription gestartet (Server)`.
* Nach einem Fallback steht dort `(Lokal-Fallback: nicht erreichbar)` bzw. `(Lokal-Fallback: Fehler)`.
* Bei rein lokaler Transkription bleibt das Verhalten unverändert (Modellname).

**Teilaufgaben:**
- [x] 1.1 `activeItem`-Suche in `updateForegroundNotification()` um `serverAttemptJob` erweitern
- [x] 1.2 Gemeinsamen Helfer `activeQueueItem()` einführen, damit die Bedingung nur noch an einer Stelle steht
- [ ] 1.3 Regressionstest: Server-Lauf zeigt Server-Badge, Fallback zeigt Fallback-Badge

---

## 2. Laufzeit-Anzeige statt eingefrorenem Prozentwert

**Priorität: 2 · Aufwand: klein · Bereich: App**

**Problem:** Ein Prozentwert, der minutenlang auf 30 steht, signalisiert dem Nutzer „hängt".

**Lösung:** Solange kein echter Fortschritt vorliegt (Phase „Server rechnet"), wird **keine
Prozentzahl** angezeigt, sondern die **verstrichene Zeit** als mitlaufender Timer, und der
Fortschrittsbalken der Benachrichtigung wird auf `indeterminate` gesetzt. Eine laufende Uhr
beweist Lebendigkeit, eine stehende Zahl nicht.

**Akzeptanzkriterien:**
* Während der Server-Wartephase zeigt Overlay, Benachrichtigung und Verlaufskarte
  `Server transkribiert · 0:47` mit sekündlich steigendem Wert.
* Der Benachrichtigungsbalken ist in dieser Phase unbestimmt (laufende Animation).
* Der Ticker stoppt sauber bei Abschluss, Fehler und Abbruch (keine freilaufende Coroutine).
* Deutsch und Englisch vorhanden.

**Teilaufgaben:**
- [x] 2.1 `QueueItem` um `serverPhase` (`UPLOADING` / `PROCESSING`) und `phaseStartedAtMs` erweitern
      *Umgesetzt mit nur diesen zwei Phasen: `preparing`/`connecting` sind bereits durch bestehende
      Status-Texte abgedeckt, ein zweites Vokabular dafür wäre reine Duplizierung.*
- [x] 2.2 Sekunden-Ticker im `serviceScope`, der nur läuft, solange ein Item in einer unbestimmten Phase ist
- [x] 2.3 `progressLabel(...)`: in Phase `processing` Prozent durch `DurationFormat`-Laufzeit ersetzen
- [x] 2.4 `updateForegroundNotification`: `setProgress(0, 0, true)` in unbestimmter Phase
- [x] 2.5 Overlay-Karte und Verlaufskarte auf dieselbe Darstellung ziehen (kein vierter Formatierungszweig)
- [x] 2.6 Localization-Keys für die neuen Texte (de/en) ergänzen
- [ ] 2.7 Ticker-Lebenszyklus testen (Abschluss / Fehler / Abbruch / `onDestroy`)

---

## 3. Echter Upload-Fortschritt

**Priorität: 3 · Aufwand: klein–mittel · Bereich: App**

**Problem:** Der Upload der Audiodatei ist heute Teil des toten Bereichs zwischen 30 % und 85 %.
Gerade im Mobilfunknetz ist er ein spürbarer Anteil der Gesamtzeit.

**Lösung:** Der `RequestBody` wird in einen zählenden Wrapper gehüllt, der beim Schreiben die
übertragenen Bytes meldet. Daraus entsteht ein *echter* Fortschritt von 0.05 bis 0.35. Der
Übergang „Upload fertig → Server rechnet" ist damit erstmals überhaupt bekannt und wird als
eigene Phase angezeigt — das ist die wichtigste Einzelinformation für den Nutzer, weil sie
belegt, dass der Server die Daten hat.

**Akzeptanzkriterien:**
* Während des Uploads bewegt sich der Balken sichtbar und zeigt `Sende Audio · 62 %`.
* Danach wechselt die Anzeige auf die Phase aus Punkt 2 (`Server transkribiert · 0:12`).
* Bei Fallback auf lokal bleibt der bisherige lokale Fortschritt unverändert.

**Teilaufgaben:**
- [x] 3.1 `CountingRequestBody` (delegierender `RequestBody` mit Byte-Callback) implementieren
- [x] 3.2 In `ServerTranscriptionEngine.transcribe()` einhängen, Mapping auf 0.05–0.35
- [x] 3.3 Phasen-Callback (`onPhase`) statt nur `onProgress` an den Service melden
- [x] 3.4 Service: Phase auf `uploading` / `processing` umschalten, Label entsprechend
- [x] 3.5 Localization-Keys für Upload-Phase (de/en)
- [x] 3.6 `ServerTranscriptionEngineTest` um Upload-Fortschritt und Phasenwechsel erweitern

---

## 4. Geschätzter Fortschritt während der Server-Wartephase

**Priorität: 4 · Aufwand: mittel · Bereich: App**

**Problem:** Auch mit Timer bleibt offen, *wie lange noch*.

**Lösung:** Das Gateway liefert bereits `durationMs` pro erfolgreichem Lauf — bislang verworfen.
Pro Modell wird ein gleitender Mittelwert der Transkriptionsdauer je Audiosekunde (bzw. je MB)
in `SettingsManager` persistiert. Daraus wird ein asymptotischer Fortschritt bis maximal 0.95
gespeist, der nie zurückspringt und nie 100 % vor dem tatsächlichen Ergebnis erreicht.

**Akzeptanzkriterien:**
* Ohne Messwerte (erster Lauf) bleibt es beim Verhalten aus Punkt 2 (reiner Timer).
* Ab dem zweiten Lauf je Modell wird eine Restzeit-Schätzung angezeigt.
* Die Schätzung ist monoton steigend und überschreitet 95 % nie vor der Antwort.

**Teilaufgaben:**
- [ ] 4.1 `durationMs` aus der Transkriptionsantwort auslesen (wird aktuell verworfen)
- [ ] 4.2 Gleitenden Mittelwert je Modell in `SettingsManager` persistieren
- [ ] 4.3 Audiolänge/Dateigröße als Bezugsgröße ermitteln
- [ ] 4.4 Asymptotische Fortschrittskurve (monoton, Deckel 0.95) implementieren
- [ ] 4.5 Restzeit-Label + Localization (de/en)

---

## 5. Retry-Logik entschärfen

**Priorität: 5 · Aufwand: klein · Bereich: App**

**Problem:** `for (attempt in 1..2)` wiederholt auch dauerhaft aussichtslose Fehler. Ein
falsch konfiguriertes Modell führt so zu 8 Minuten Wartezeit statt zu sofortigem Fallback.

**Lösung:** Nur wiederholen, was plausibel transient ist (Verbindungsabbruch, Timeout, HTTP 5xx).
Bei HTTP 4xx sofort abbrechen und lokal weitermachen; der Grund wird als Fallback-Badge
angezeigt.

**Akzeptanzkriterien:**
* HTTP 400/404 → kein zweiter Versuch, sofortiger lokaler Fallback.
* Verbindungsabbruch/5xx → weiterhin ein zweiter Versuch.
* Der Fallback-Grund ist für den Nutzer sichtbar unterscheidbar.

**Teilaufgaben:**
- [ ] 5.1 Typisierte Exception mit HTTP-Status aus `transcribe()` werfen
- [ ] 5.2 Retry-Entscheidung anhand Status/Fehlerart in `attemptServerEagerly`
- [ ] 5.3 Fallback-Grund um „Serverfehler (HTTP x)" erweitern + Localization
- [ ] 5.4 Test: 4xx wiederholt nicht, 5xx wiederholt

---

## 6. Benachrichtigungs-Updates entschlacken

**Priorität: 6 · Aufwand: klein · Bereich: App (Performance)**

**Problem:** Pro Fortschritts-Tick werden zwei `PendingIntent`s und ein kompletter Builder neu
erzeugt und `startForeground()` erneut aufgerufen. Mit echtem Upload-Fortschritt (Punkt 3)
steigt die Tick-Frequenz deutlich — der Aufwand wird dann relevant.

**Akzeptanzkriterien:**
* `PendingIntent`s werden einmalig erzeugt und wiederverwendet.
* Benachrichtigungs-Updates sind auf max. ~1/s begrenzt, außer der Statustext ändert sich.
* Keine sichtbare Verzögerung bei Phasenwechseln.

**Teilaufgaben:**
- [ ] 6.1 `PendingIntent`s (Cancel/Switch) einmalig als `lazy`-Felder halten
- [ ] 6.2 Builder wiederverwenden statt pro Tick neu aufbauen
- [ ] 6.3 Update-Drossel (1/s, Bypass bei Textänderung) analog `syncItemToDatabase`
- [ ] 6.4 Gegenprüfung: Phasenwechsel kommen ohne Verzögerung durch

---

## 7. Persistenter Serverstatus in der App

**Priorität: 7 · Aufwand: mittel · Bereich: App**

**Problem:** Der Health-Check passiert erst *im* Transkriptionslauf. Vorher weiß der Nutzer nie,
ob der Server erreichbar ist.

**Lösung:** Statusanzeige (Chip) im Hauptbildschirm mit Ergebnis und Zeitpunkt des letzten
Health-Checks, plus Vorwärmen beim Öffnen der App bzw. beim Teilen einer Datei.

**Akzeptanzkriterien:**
* Chip zeigt `Server erreichbar · german-turbo` bzw. `Server nicht erreichbar` inkl. Alter der Info.
* Der Check blockiert den Start der Transkription nicht.
* Bei deaktivierter Servernutzung ist der Chip ausgeblendet.

**Teilaufgaben:**
- [ ] 7.1 Health-Status samt Zeitstempel im `MainViewModel` halten
- [ ] 7.2 Chip-Komponente in `MainScreen` einbauen
- [ ] 7.3 Vorwärmen bei App-Start und in `ShareActivity`
- [ ] 7.4 Localization (de/en)
- [ ] 7.5 Verhalten bei deaktivierter Servernutzung prüfen

---

## 8. Job-API im Gateway (echte Serverphasen)

**Priorität: 8 · Aufwand: groß · Bereich: Server + App**

**Problem:** Ein einzelner blockierender HTTP-Call überlebt weder App-Neustart noch
WLAN→Mobilfunk-Wechsel, und der Server kann währenddessen nichts über sich mitteilen.

**Lösung:** `POST /v1/transcribe` antwortet mit `{ jobId }`; `GET /v1/jobs/:id` liefert
`queued | loading_model | running | done | error` samt `elapsedMs`. Der Upstream
(whisper-asr-webservice) liefert selbst keinen Fortschritt — die Phasen-Information allein ist
aber bereits deutlich mehr als heute, und ein laufender Job bleibt nach App-Neustart auffindbar.

**Hinweis zur Reihenfolge:** Laut `AI_CONTEXT.md` §B3 wird bei Änderungen an beiden Seiten
**zuerst die App** committet und gepusht (langsame Release-CI), danach der Server.

**Teilaufgaben:**
- [ ] 8.1 Job-Registry im Gateway (In-Memory, TTL, Aufräumen)
- [ ] 8.2 `POST /v1/transcribe` auf Job-Antwort umstellen (abwärtskompatibel per Header/Flag)
- [ ] 8.3 `GET /v1/jobs/:id` mit Phase + `elapsedMs`
- [ ] 8.4 `/v1/cancel` auf `jobId` umstellen
- [ ] 8.5 OpenAPI-Spezifikation aktualisieren
- [ ] 8.6 Server-Tests (Registry, Polling, Abbruch, TTL)
- [ ] 8.7 App: Polling-Client mit Backoff
- [ ] 8.8 App: laufenden Job nach Neustart wieder aufgreifen

---

## 9. Modell warmhalten

**Priorität: 9 · Aufwand: mittel · Bereich: Server**

**Problem:** Ein Kaltstart des Modells kostet laut vorhandener Messung 25–30 s — auf kurze
Sprachnachrichten bezogen ist das der dominierende Anteil.

**Akzeptanzkriterien:**
* Optionales Vorwärmen des Zielmodells (konfigurierbar, standardmäßig aus).
* Kein Dauerbetrieb ungenutzter Modelle (RAM-Budget des Hosts beachten).

**Teilaufgaben:**
- [ ] 9.1 Vorwärm-Endpunkt bzw. Vorwärmen im Health-Check (optional, konfigurierbar)
- [ ] 9.2 Leerlauf-Abschaltung, damit kein Modell dauerhaft RAM hält
- [ ] 9.3 App: Vorwärmen beim Teilen anstoßen
- [ ] 9.4 Messung Kaltstart vs. warm dokumentieren

---

## 10. Timeout-Kaskade staffeln

**Priorität: 10 · Aufwand: klein · Bereich: App + Server**

**Problem:** App-, Gateway- und Upstream-Timeout liegen alle bei 240 s. Die App kann aufgeben,
während das Gateway gerade antwortet — der Nutzer sieht dann einen Fallback, obwohl der Server
erfolgreich war.

**Teilaufgaben:**
- [ ] 10.1 App-Timeout auf ~270 s anheben (mit Begründung im Kommentar)
- [ ] 10.2 Gateway-Timeout gegen Upstream auf 240 s belassen und dokumentieren
- [ ] 10.3 Kommentar-Querverweise in beiden Repos aktualisieren

---

## 11. Abschlussarbeiten je Umsetzungspaket

- [ ] 11.1 `AI_CONTEXT.md` um die neuen Phasen/Status-Konventionen ergänzen
- [ ] 11.2 Eintrag in `../vibe_projects/CHANGELOG.md`
- [ ] 11.3 Commit (Conventional Commits) + Push auf `main` → Release-CI
- [ ] 11.4 Gateway-Container neu starten, sofern serverseitig etwas geändert wurde

---

## Umsetzungsreihenfolge

**Paket A (jetzt):** Punkte 1 – 3. Rein App-seitig, behebt beide gemeldeten Symptome.
**Paket B:** Punkte 4 – 6. Feinschliff Fortschritt + Performance.
**Paket C:** Punkt 7.
**Paket D:** Punkte 8 – 10. Erfordert Server-Änderungen und eine eigene Testrunde.
