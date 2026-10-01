package salah.cli

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import salah.core.AppInstance
import salah.core.ConfigException
import salah.core.ConfigKeyException
import salah.core.ConfigKeys
import salah.core.ConfigStore
import salah.core.DaySchedule
import salah.core.HijriDate
import salah.core.LocationSearch
import salah.core.LocationSearchException
import salah.core.MadhabSetting
import salah.core.MethodID
import salah.core.NotificationPlanner
import salah.core.Prayer
import salah.core.PrayerClock
import salah.core.PrayerClockState
import salah.core.PrayerSchedule
import salah.core.SalahConfig
import salah.core.SalahInfo
import salah.core.SavedLocation
import salah.core.TimeFormatting
import salah.core.localDate
import salah.core.monthName
import salah.core.parseLocalDate
import salah.core.weekdayName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    System.setOut(Terminal.out)
    System.setErr(Terminal.err)
    exitProcess(Cli.run(args.toList()))
}

object Cli {
    val store: ConfigStore get() = ConfigStore()

    /** Parses and runs a command, mapping every failure to the documented exit codes. */
    fun run(arguments: List<String>): Int = try {
        dispatch(arguments)
        0
    } catch (e: HelpRequested) {
        Terminal.write(e.text)
        0
    } catch (e: CliError) {
        Terminal.printError(e.message)
        e.code
    } catch (e: ConfigException) {
        Terminal.printError(e.message + " Run `salah config reset` to restore defaults, then `salah setup`.")
        CliError.MISSING_CONFIG
    } catch (e: ConfigKeyException) {
        Terminal.printError(e.message ?: "$e")
        CliError.USAGE
    } catch (e: LocationSearchException) {
        Terminal.printError(e.message ?: "$e")
        CliError.RUNTIME
    } catch (e: Exception) {
        Terminal.printError(e.message ?: e.toString())
        CliError.RUNTIME
    }

    private fun dispatch(a: List<String>) {
        if (a.firstOrNull() == "--version") return Terminal.write(SalahInfo.VERSION + "\n")
        if (a.firstOrNull() == "help") return Terminal.write(helpFor(a.drop(1)))
        val sub = a.firstOrNull()?.takeIf { !it.startsWith("-") }
        val rest = if (sub != null) a.drop(1) else a
        when (sub) {
            null, "today" -> if (sub == null && rest.any { it == "-h" || it == "--help" }) throw HelpRequested(Help.ROOT) else today(rest)
            "next" -> next(rest)
            "schedule" -> schedule(rest)
            "location" -> location(rest)
            "setup" -> setup(rest)
            "config" -> config(rest)
            "reminders" -> reminders(rest)
            else -> throw CliError.usage("Unexpected argument '$sub'.\n\n${Help.ROOT}")
        }
    }

    private fun helpFor(path: List<String>): String = when (path.joinToString(" ")) {
        "today" -> Help.TODAY
        "next" -> Help.NEXT
        "schedule" -> Help.SCHEDULE
        "location" -> Help.LOCATION
        "location set" -> Help.LOCATION_SET
        "setup" -> Help.SETUP
        "config" -> Help.CONFIG
        "reminders" -> Help.REMINDERS
        else -> Help.ROOT
    }

    // region Shared

    fun loadConfig(): SalahConfig = store.load()

    fun requireLocation(config: SalahConfig): SavedLocation {
        val loc = config.location ?: throw CliError.missingLocation()
        if (!loc.isValid) throw CliError(CliError.MISSING_CONFIG, "The saved location is invalid. Run `salah setup` or `salah location set <city>`.")
        return loc
    }

    /** The current instant. `SALAH_NOW` (ISO 8601) overrides it for deterministic tests. */
    fun now(): Instant = System.getenv("SALAH_NOW")?.let { salah.core.Iso.parse(it) } ?: Instant.now()

    fun parseDate(s: String?): LocalDate? {
        s ?: return null
        return parseLocalDate(s) ?: throw CliError.usage("Invalid date “$s”. Use YYYY-MM-DD.")
    }

    val appIsRunning: Boolean get() = AppInstance.isRunning()

    private fun appName() = "the Salah app"

    // endregion

    // region today / next / schedule

    private fun today(tokens: List<String>) {
        val a = Args(tokens, Args.OUTPUT_FLAGS, setOf("date"), Help.TODAY)
        a.noExtraPositionals()
        val output = a.output()
        val date = parseDate(a.option("date"))
        val config = loadConfig()
        val location = requireLocation(config)
        Terminal.write(renderToday(config, location, now(), date, output))
    }

    fun renderToday(config: SalahConfig, location: SavedLocation, now: Instant, date: LocalDate?, output: OutputOptions): String {
        val state = PrayerClock.state(now, location, config.calculation, config.display.nowWindowMinutes)
        val isToday = date == null || date == state.today.date
        val schedule = if (isToday) state.today else PrayerSchedule.forDate(date!!, location, config.calculation)
        val d = config.display
        return when (output.mode) {
            OutputMode.JSON -> JsonOutput.encode(buildJsonObject {
                put("schemaVersion", JsonOutput.SCHEMA_VERSION)
                put("generatedAt", TimeFormatting.iso8601(now, location.zone))
                put("location", JsonOutput.location(location))
                put("method", JsonOutput.method(config))
                put("day", JsonOutput.day(schedule, config))
                put("next", if (isToday) JsonOutput.next(state.next, now, location.zone, d.jumuahRelabel) else JsonNull)
                put("now", if (isToday) JsonOutput.now(state, config) else JsonNull)
                put("currentPeriod", if (isToday) state.current?.raw?.let { JsonPrimitive(it) } ?: JsonNull else JsonNull)
            })
            OutputMode.COMPACT -> compactLine(state, schedule, isToday, config) + "\n"
            else -> {
                val sections = mutableListOf(DayView.header(schedule.date, location, config))
                if (isToday) sections += nextSection(state, config)
                sections += DayView.rows(schedule, config, if (isToday) state else null) + DayView.undefinedNote(schedule)
                output.renderer.render(sections)
            }
        }
    }

    fun nextSection(state: PrayerClockState, config: SalahConfig): List<Line> {
        val d = config.display
        val zone = state.today.zone
        val lines = mutableListOf<Line>()
        val p = state.nowPrayer
        val t = p?.let { state.today.time(it) }
        if (p != null && t != null) {
            lines += listOf(Span.dim("NOW"))
            lines += listOf(
                Span.accent(state.today.label(p, d.jumuahRelabel).uppercase()),
                Span.normal("  " + DayView.time(t, zone, d)),
                Span.dim("  STARTED " + TimeFormatting.countdown(state.secondsSinceNow ?: 0.0)),
            )
        }
        val n = state.next
        if (n != null) {
            lines += listOf(Span.dim(if (n.isTomorrow) "NEXT PRAYER · TOMORROW" else "NEXT PRAYER"))
            lines += listOf(
                Span.bold(n.label(d.jumuahRelabel).uppercase()),
                Span.normal("  " + DayView.time(n.time, zone, d)),
                Span.accent("  IN " + TimeFormatting.countdown(n.secondsRemaining(state.now))),
            )
        } else {
            lines += listOf(Span.dim("NEXT PRAYER"))
            lines += listOf(Span.normal("— can't be calculated for this location"))
        }
        return lines
    }

    /** e.g. "Isha 20:10 (42m)", or "Asr now · Maghrib 18:55 (2h 50m)" inside the NOW window. */
    fun compactLine(state: PrayerClockState, schedule: DaySchedule, isToday: Boolean, config: SalahConfig): String {
        val d = config.display
        val zone = schedule.zone
        if (!isToday) {
            return Prayer.entries.joinToString(" · ") { p ->
                "${schedule.label(p, d.jumuahRelabel)} ${schedule.time(p)?.let { TimeFormatting.clock(it, zone, d.use24HourClock) } ?: "—"}"
            }
        }
        val parts = mutableListOf<String>()
        state.nowPrayer?.let { parts += "${state.today.label(it, d.jumuahRelabel)} now" }
        state.next?.let { n ->
            parts += "${n.label(d.jumuahRelabel)} ${TimeFormatting.clock(n.time, zone, d.use24HourClock)} (${TimeFormatting.short(n.secondsRemaining(state.now))})"
        }
        return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
    }

    private fun next(tokens: List<String>) {
        val a = Args(tokens, Args.OUTPUT_FLAGS + "watch", emptySet(), Help.NEXT)
        a.noExtraPositionals()
        val output = a.output()
        val watch = a.flag("watch")
        if (watch && output.json) throw CliError.usage("--watch can't be combined with --json.")
        val config = loadConfig()
        val location = requireLocation(config)
        if (!watch) return Terminal.write(renderNext(config, location, now(), output))

        val tty = Terminal.stdoutIsTTY
        val style = Style(output.renderer.color)
        Runtime.getRuntime().addShutdownHook(Thread { Terminal.out.print("\n"); Terminal.out.flush() })
        while (true) {
            val now = Instant.now()
            val line = watchLine(config, location, now, style)
            Terminal.write(if (tty) "\r\u001B[2K$line" else line + "\n")
            // Sleep to the next whole second so the countdown ticks evenly.
            Thread.sleep(1000 - now.toEpochMilli() % 1000)
        }
    }

    fun renderNext(config: SalahConfig, location: SavedLocation, now: Instant, output: OutputOptions): String {
        val st = PrayerClock.state(now, location, config.calculation, config.display.nowWindowMinutes)
        return when (output.mode) {
            OutputMode.JSON -> JsonOutput.encode(buildJsonObject {
                put("schemaVersion", JsonOutput.SCHEMA_VERSION)
                put("generatedAt", TimeFormatting.iso8601(now, location.zone))
                put("location", JsonOutput.location(location))
                put("method", JsonOutput.method(config))
                put("next", JsonOutput.next(st.next, now, location.zone, config.display.jumuahRelabel))
                put("now", JsonOutput.now(st, config))
            })
            OutputMode.COMPACT -> compactLine(st, st.today, true, config) + "\n"
            else -> {
                val header: List<Line> = listOf(listOf(Span.dim("${location.name.uppercase()} · ${config.calculation.methodShortName(location).uppercase()}")))
                output.renderer.render(listOf(header, nextSection(st, config)))
            }
        }
    }

    private fun watchLine(config: SalahConfig, location: SavedLocation, now: Instant, style: Style): String {
        val st = PrayerClock.state(now, location, config.calculation, config.display.nowWindowMinutes)
        val d = config.display
        val n = st.next ?: return "Next prayer can't be calculated for this location"
        var s = "${style.bold(n.label(d.jumuahRelabel).uppercase())}  " +
            TimeFormatting.clock(n.time, location.zone, d.use24HourClock) +
            "  " + style.accent("IN " + TimeFormatting.countdown(n.secondsRemaining(now)))
        st.nowPrayer?.let { p -> s = style.accent("${st.today.label(p, d.jumuahRelabel).uppercase()} NOW") + "  ·  " + s }
        return s
    }

    private fun schedule(tokens: List<String>) {
        val a = Args(tokens, Args.OUTPUT_FLAGS + setOf("week", "month"), setOf("date"), Help.SCHEDULE)
        a.noExtraPositionals()
        val output = a.output()
        val date = parseDate(a.option("date"))
        if (a.flag("week") && a.flag("month")) throw CliError.usage("Use either --week or --month, not both.")
        val config = loadConfig()
        val location = requireLocation(config)
        val now = now()
        val start = date ?: localDate(now, location.zone)
        val period = if (a.flag("week")) Period.WEEK else if (a.flag("month")) Period.MONTH else Period.DAY
        Terminal.write(renderSchedule(config, location, now, start, period, output))
    }

    enum class Period { DAY, WEEK, MONTH }

    fun renderSchedule(config: SalahConfig, location: SavedLocation, now: Instant, start: LocalDate, period: Period, output: OutputOptions): String {
        val days = when (period) {
            Period.DAY -> listOf(PrayerSchedule.forDate(start, location, config.calculation))
            Period.WEEK -> PrayerSchedule.range(start, 7, location, config.calculation)
            Period.MONTH -> PrayerSchedule.month(start, location, config.calculation)
        }
        val d = config.display
        val today = localDate(now, location.zone)
        return when (output.mode) {
            OutputMode.JSON -> JsonOutput.encode(buildJsonObject {
                put("schemaVersion", JsonOutput.SCHEMA_VERSION)
                put("generatedAt", TimeFormatting.iso8601(now, location.zone))
                put("location", JsonOutput.location(location))
                put("method", JsonOutput.method(config))
                put("days", kotlinx.serialization.json.JsonArray(days.map { JsonOutput.day(it, config) }))
            })
            OutputMode.COMPACT -> days.joinToString("\n") { s ->
                "${s.date} " + Prayer.entries.joinToString(" · ") { p ->
                    "${s.label(p, d.jumuahRelabel)} ${s.time(p)?.let { TimeFormatting.clock(it, s.zone, d.use24HourClock) } ?: "—"}"
                }
            } + "\n"
            else -> if (period == Period.DAY) {
                val s = days[0]
                output.renderer.render(listOf(DayView.header(s.date, location, config), DayView.rows(s, config, null) + DayView.undefinedNote(s)))
            } else {
                output.renderer.render(table(days, location, config, today))
            }
        }
    }

    private fun table(days: List<DaySchedule>, location: SavedLocation, config: SalahConfig, today: LocalDate): List<List<Line>> {
        val d = config.display
        val first = days.first().date
        val last = days.last().date
        val range = "${first.dayOfMonth} ${first.monthName.take(3).uppercase()} – ${last.dayOfMonth} ${last.monthName.take(3).uppercase()} ${last.year}"
        val header: List<Line> = listOf(
            listOf(Span.bold("SALAH · SCHEDULE")),
            listOf(Span.normal(range)),
            listOf(Span.dim("${location.name.uppercase()} · ${config.calculation.methodShortName(location).uppercase()}")),
        )
        val timeWidth = if (d.use24HourClock) 5 else 8
        val dateWidth = 12
        fun col(s: String, w: Int) = Renderer.pad(s, w + 2)
        val rows = mutableListOf<Line>(listOf(Span.dim(col("Date", dateWidth) + Prayer.entries.joinToString("") { col(it.displayName, maxOf(timeWidth, 7)) })))
        var hasFriday = false
        for (s in days) {
            val dateLabel = "${s.date.weekdayName.take(3)} ${s.date.dayOfMonth} ${s.date.monthName.take(3)}"
            val cells = Prayer.entries.joinToString("") { p ->
                var t = DayView.time(s.time(p), s.zone, d)
                if (p == Prayer.DHUHR && s.isFriday && d.jumuahRelabel) { t += "*"; hasFriday = true }
                col(t, maxOf(timeWidth, 7))
            }
            rows += listOf(Span(col(dateLabel, dateWidth) + cells, if (s.date == today) Span.Kind.BOLD else Span.Kind.NORMAL))
        }
        val notes = mutableListOf<Line>(listOf(Span.dim("Sunrise marks the end of Fajr and is not a prayer.")))
        if (hasFriday) notes.add(0, listOf(Span.dim("* Jumu'ah")))
        if (days.any { it.hasUndefined }) notes += listOf(Span.accent("— means the time can't be calculated here on that date."))
        return listOf(header, rows, notes)
    }

    // endregion

    // region location / setup

    private fun location(tokens: List<String>) {
        when (tokens.firstOrNull()?.takeIf { !it.startsWith("-") }) {
            null, "show" -> locationShow(if (tokens.firstOrNull() == "show") tokens.drop(1) else tokens)
            "set" -> locationSet(tokens.drop(1))
            else -> throw CliError.usage("Unexpected argument '${tokens.first()}'.\n\n${Help.LOCATION}")
        }
    }

    private fun locationShow(tokens: List<String>) {
        val a = Args(tokens, Args.OUTPUT_FLAGS, emptySet(), Help.LOCATION)
        a.noExtraPositionals()
        val output = a.output()
        val config = loadConfig()
        val loc = requireLocation(config)
        val auto = if (config.calculation.method == null) " (automatic)" else ""
        Terminal.write(
            when (output.mode) {
                OutputMode.JSON -> JsonOutput.encode(JsonOutput.location(loc))
                OutputMode.COMPACT -> "${loc.name} (${loc.timeZone})\n"
                else -> output.renderer.render(listOf(listOf(
                    listOf(Span.bold(loc.name.uppercase())),
                    listOf(Span.normal(loc.coordinateDescription)),
                    listOf(Span.normal(loc.timeZone)),
                    listOf(Span.dim(if (loc.source == SavedLocation.Source.AUTOMATIC) "From Windows location" else "Entered manually")),
                    listOf(Span.dim("Method: ${config.methodName}$auto")),
                )))
            },
        )
    }

    private fun locationSet(tokens: List<String>) {
        val a = Args(tokens, setOf("first"), setOf("lat", "lon", "tz", "name"), Help.LOCATION_SET)
        val lat = a.double("lat")
        val lon = a.double("lon")
        val tz = a.option("tz")
        val hasQuery = a.positionals.isNotEmpty()
        val hasCoords = lat != null || lon != null
        if (hasQuery == hasCoords) throw CliError.usage("Give either a place to search for, or --lat and --lon.")
        if (hasCoords) {
            if (lat == null || lon == null) throw CliError.usage("Both --lat and --lon are required.")
            if (lat !in -90.0..90.0) throw CliError.usage("--lat must be between -90 and 90.")
            if (lon !in -180.0..180.0) throw CliError.usage("--lon must be between -180 and 180.")
        }
        if (tz != null && SavedLocation.zoneOrNull(tz) == null) {
            throw CliError.usage("Unknown time zone “$tz”. Use an IANA name such as Asia/Singapore.")
        }
        val location = if (lat != null && lon != null) {
            fromCoordinates(lat, lon, tz, a.option("name"))
        } else {
            choose(LocationSearch.search(a.positionals.joinToString(" ")), pickFirst = a.flag("first") || !Terminal.stdinIsTTY)
        }
        val config = store.update { it.copy(location = location) }
        println("Location set to ${location.name} (${location.timeZone} · ${location.coordinateDescription}).")
        println("Method: ${config.methodName}${if (config.calculation.method == null) " (automatic)" else ""}")
    }

    fun fromCoordinates(lat: Double, lon: Double, tz: String?, name: String?): SavedLocation {
        val found = LocationSearch.reverse(lat, lon, SavedLocation.Source.MANUAL)
        val zone = tz ?: found?.timeZone
            ?: throw CliError(CliError.RUNTIME, "Couldn't look up the time zone for these coordinates. Pass --tz <IANA>, e.g. --tz Asia/Jakarta.")
        return SavedLocation(
            name = name ?: found?.name ?: String.format(Locale.ROOT, "%.4f, %.4f", lat, lon),
            latitude = lat, longitude = lon, timeZone = zone, countryCode = found?.countryCode,
        )
    }

    fun choose(results: List<SavedLocation>, pickFirst: Boolean): SavedLocation {
        if (results.size == 1 || pickFirst) return results[0]
        results.forEachIndexed { i, r -> println("  ${i + 1}) ${LocationSearch.detail(r)}") }
        print("Choose [1]: ")
        System.out.flush()
        val answer = readlnOrNull()?.trim() ?: ""
        if (answer.isEmpty()) return results[0]
        val n = answer.toIntOrNull()
        if (n == null || n !in 1..results.size) throw CliError.usage("Invalid choice “$answer”.")
        return results[n - 1]
    }

    private fun setup(tokens: List<String>) {
        Args(tokens, emptySet(), emptySet(), Help.SETUP).noExtraPositionals()
        if (!Terminal.stdinIsTTY) {
            throw CliError.usage("`salah setup` needs an interactive terminal. Use `salah location set` and `salah config set` in scripts.")
        }
        val store = store
        var config = runCatching { store.load() }.getOrDefault(SalahConfig.DEFAULT)
        println("Salah setup\n")
        config = config.copy(location = askLocation(config.location))

        val auto = MethodID.automatic(config.location)
        println("\nCalculation method")
        println("  0) Automatic — ${auto.displayName}")
        MethodID.entries.forEachIndexed { i, m -> println("  ${i + 1}) ${m.displayName}") }
        val choice = ask("Choose", "0") { it.toIntOrNull()?.takeIf { n -> n in 0..MethodID.entries.size } }
        var calc = config.calculation.copy(method = if (choice == 0) null else MethodID.entries[choice - 1])
        if (calc.method == MethodID.CUSTOM) {
            calc = calc.copy(
                customFajrAngle = ask("Fajr angle", "20") { it.toDoubleOrNull()?.takeIf { v -> v in 0.0..30.0 } },
                customIshaAngle = ask("Isha angle", "18") { it.toDoubleOrNull()?.takeIf { v -> v in 0.0..30.0 } },
            )
        }
        println("\nAsr")
        println("  1) ${MadhabSetting.SHAFI.displayName}")
        println("  2) ${MadhabSetting.HANAFI.displayName}")
        calc = calc.copy(madhab = ask("Choose", "1") { mapOf("1" to MadhabSetting.SHAFI, "2" to MadhabSetting.HANAFI)[it] })
        val use24 = ask("\nClock, 12 or 24", if (config.display.use24HourClock) "24" else "12") { mapOf("12" to false, "24" to true)[it] }
        config = config.copy(calculation = calc, display = config.display.copy(use24HourClock = use24))
        store.save(config)
        println("\nSaved to ${store.path}.")
        println("Run `salah` to see today's times. Reminders are delivered by the Salah app.")
    }

    private fun askLocation(current: SavedLocation?): SavedLocation {
        while (true) {
            val hint = current?.let { " [${it.name}]" } ?: ""
            print("Where are you? City, or “lat, lon”$hint: ")
            System.out.flush()
            val answer = readInput()
            if (answer.isEmpty() && current != null) return current
            if (answer.isEmpty()) continue
            val nums = answer.split(",").map { it.trim().toDoubleOrNull() }
            try {
                if (nums.size == 2 && nums[0] != null && nums[1] != null && nums[0]!! in -90.0..90.0 && nums[1]!! in -180.0..180.0) {
                    return fromCoordinates(nums[0]!!, nums[1]!!, null, null)
                }
                val loc = choose(LocationSearch.search(answer), pickFirst = false)
                println("→ ${LocationSearch.detail(loc)}")
                return loc
            } catch (e: Exception) {
                Terminal.printError(e.message ?: "$e")
            }
        }
    }

    /** Reads a line; exits cleanly when input ends (Ctrl-Z/Ctrl-D) instead of looping forever. */
    private fun readInput(): String {
        val line = readlnOrNull()
        if (line == null) {
            println("\nSetup cancelled. Nothing was saved.")
            throw CliError(CliError.RUNTIME, "Setup cancelled.")
        }
        return line.trim()
    }

    private fun <T> ask(prompt: String, default: String, parse: (String) -> T?): T {
        while (true) {
            print("$prompt [$default]: ")
            System.out.flush()
            val raw = readInput()
            parse(raw.ifEmpty { default })?.let { return it }
            println("Please enter a valid value.")
        }
    }

    // endregion

    // region config / reminders

    private fun config(tokens: List<String>) {
        val sub = tokens.firstOrNull()?.takeIf { !it.startsWith("-") && it in setOf("get", "set", "path", "reset") }
        val rest = if (sub != null) tokens.drop(1) else tokens
        when (sub ?: "get") {
            "get" -> {
                val a = Args(rest, Args.OUTPUT_FLAGS, emptySet(), Help.CONFIG)
                a.noExtraPositionals(1)
                val output = a.output()
                val config = loadConfig()
                val key = a.positionals.firstOrNull()
                if (key != null) {
                    val k = ConfigKeys.find(key)
                    return Terminal.write(if (output.mode == OutputMode.JSON) JsonOutput.encode(buildJsonObject { put(key, k.get(config)) }) else k.get(config) + "\n")
                }
                if (output.mode == OutputMode.JSON) {
                    return Terminal.write(JsonOutput.encode(JsonObject(ConfigKeys.all.associate { it.key to JsonPrimitive(it.get(config)) })))
                }
                val width = ConfigKeys.all.maxOf { it.key.length }
                val style = Style(output.renderer.color)
                val sb = StringBuilder()
                for (k in ConfigKeys.all) {
                    val v = k.get(config)
                    sb.append(Renderer.pad(k.key, width)).append("  ").append(if (v.isEmpty()) style.dim("—") else v).append('\n')
                }
                Terminal.write(sb.toString())
            }
            "set" -> {
                val a = Args(rest, emptySet(), emptySet(), Help.CONFIG)
                if (a.positionals.size != 2) throw CliError.usage("Usage: salah config set <key> <value>")
                val (key, value) = a.positionals
                val k = ConfigKeys.find(key)
                val config = store.update { k.set(it, value) }
                println("$key = ${k.get(config)}")
                if (appIsRunning) println("The Salah app is running and will pick up the change.")
            }
            "path" -> {
                Args(rest, emptySet(), emptySet(), Help.CONFIG).noExtraPositionals()
                println(store.path.toString())
            }
            "reset" -> {
                val a = Args(rest, setOf("all"), emptySet(), Help.CONFIG)
                a.noExtraPositionals()
                val all = a.flag("all")
                // A corrupt file is exactly when reset is needed, so don't require it to load.
                val old = runCatching { store.load() }.getOrNull()
                val fresh = if (all) SalahConfig.DEFAULT else SalahConfig.DEFAULT.copy(location = old?.location)
                store.save(fresh)
                println(
                    if (all || fresh.location == null) "Config reset to defaults."
                    else "Config reset to defaults (location kept: ${fresh.location?.name}).",
                )
            }
        }
    }

    private fun reminders(tokens: List<String>) {
        val sub = tokens.firstOrNull()?.takeIf { it in setOf("status", "enable", "disable") }
        val rest = if (sub != null) tokens.drop(1) else tokens
        when (sub ?: "status") {
            "status" -> {
                val a = Args(rest, Args.OUTPUT_FLAGS, emptySet(), Help.REMINDERS)
                a.noExtraPositionals()
                Terminal.write(renderReminders(loadConfig(), now(), appIsRunning, a.output()))
            }
            "enable", "disable" -> {
                val on = sub == "enable"
                val a = Args(rest, emptySet(), setOf("prayer"), Help.REMINDERS)
                a.noExtraPositionals()
                val prayer = a.option("prayer")?.let { name ->
                    Prayer.fromName(name)?.takeIf { it.isPrayer }
                        ?: throw CliError.usage("Unknown prayer “$name”. Use fajr, dhuhr, asr, maghrib or isha.")
                }
                store.update { c ->
                    if (prayer != null) {
                        c.copy(reminders = c.reminders.update(prayer) { it.copy(enabled = on) })
                    } else if (on) {
                        c.copy(reminders = c.reminders.copy(enabled = true).withPausedUntil(null))
                    } else {
                        c.copy(reminders = c.reminders.copy(enabled = false))
                    }
                }
                val what = prayer?.let { "${it.displayName} reminders ${if (on) "on" else "off"}." } ?: "Reminders ${if (on) "on" else "off"}."
                println(savedMessage(what, appIsRunning))
            }
        }
    }

    /** Never claims notifications are scheduled: only the app delivers them. */
    fun savedMessage(what: String, appRunning: Boolean): String =
        if (appRunning) "Saved. $what The Salah app is running and will update its reminders."
        else "Saved. $what Reminders take effect when the Salah app is running."

    fun renderReminders(config: SalahConfig, now: Instant, appRunning: Boolean, output: OutputOptions): String {
        val r = config.reminders
        val zone = config.location?.zone ?: ZoneId.systemDefault()
        val planned = NotificationPlanner.plan(now, config)
        val paused = r.isPaused(now)
        return when (output.mode) {
            OutputMode.JSON -> JsonOutput.encode(buildJsonObject {
                put("enabled", r.enabled)
                put("paused", paused)
                put("pausedUntil", r.pausedUntil?.let { JsonPrimitive(TimeFormatting.iso8601(it, zone)) } ?: JsonNull)
                put("sound", r.sound.raw)
                put("quietHours", buildJsonObject {
                    put("enabled", r.quietHours.enabled); put("start", r.quietHours.start); put("end", r.quietHours.end)
                })
                put("prayers", JsonObject(r.prayers.mapValues { (_, v) ->
                    buildJsonObject { put("enabled", v.enabled); put("leadMinutes", v.leadMinutes); put("atTime", v.atTime) }
                }))
                put("appRunning", appRunning)
                put("plannedCount", planned.size)
                put("nextReminder", planned.firstOrNull()?.let { JsonPrimitive(TimeFormatting.iso8601(it.fireDate, zone)) } ?: JsonNull)
            })
            OutputMode.COMPACT -> {
                val state = if (!r.enabled) "off" else if (paused) "paused" else "on"
                "reminders $state · ${planned.size} planned · app ${if (appRunning) "running" else "not running"}\n"
            }
            else -> {
                val stateText = if (!r.enabled) "OFF" else if (paused) "PAUSED" else "ON"
                val head = mutableListOf<Line>(
                    listOf(Span.bold("REMINDERS  "), if (r.enabled && !paused) Span.accent(stateText) else Span.dim(stateText)),
                    listOf(Span.dim(if (appRunning) "The Salah app is running" else "The Salah app is not running — reminders are delivered only by the app")),
                )
                val until = r.pausedUntil
                if (paused && until != null) {
                    head += listOf(Span.normal("Paused until ${TimeFormatting.shortDate(localDate(until, zone))} ${TimeFormatting.clock(until, zone, config.display.use24HourClock)}"))
                }
                val rows = Prayer.prayers.map { p ->
                    val pr = r.reminder(p)
                    val desc = if (!pr.enabled) "off" else {
                        val parts = mutableListOf<String>()
                        if (pr.leadMinutes > 0) parts += "${pr.leadMinutes} min before"
                        if (pr.atTime) parts += "at prayer time"
                        if (parts.isEmpty()) "off" else parts.joinToString(" + ")
                    }
                    listOf(Span.normal(Renderer.pad(p.displayName, 11)), if (pr.enabled && r.enabled) Span.normal(desc) else Span.dim(desc))
                }
                val foot = mutableListOf<Line>(
                    listOf(Span.normal("Sound: ${r.sound.displayName}")),
                    listOf(Span.normal("Quiet hours: " + if (r.quietHours.enabled) "${r.quietHours.start}–${r.quietHours.end}" else "off")),
                )
                planned.firstOrNull()?.let { first ->
                    val whenText = "${TimeFormatting.shortDate(first.date)} ${TimeFormatting.clock(first.fireDate, zone, config.display.use24HourClock)}"
                    foot += listOf(Span.dim("Next: ${first.title} · $whenText"))
                    foot += listOf(Span.dim("${planned.size} reminders in the next ${NotificationPlanner.DEFAULT_DAYS} days"))
                }
                foot += listOf(Span.dim("Reminders arrive while the Salah app runs (it starts with Windows)."))
                output.renderer.render(listOf(head, rows, foot))
            }
        }
    }

    // endregion
}

/** Builders for the day view shared by `today` and `schedule`. */
object DayView {
    fun time(t: Instant?, zone: ZoneId, display: salah.core.DisplaySettings): String =
        t?.let { TimeFormatting.clock(it, zone, display.use24HourClock, padHour = true) } ?: "—"

    fun header(day: LocalDate, location: SavedLocation, config: SalahConfig, title: String = "SALAH"): List<Line> = listOf(
        listOf(Span.bold(title)),
        listOf(Span.normal(TimeFormatting.longDate(day).uppercase())),
        listOf(Span.dim(HijriDate.of(day, config.display.hijriAdjustment).formatted.uppercase())),
        listOf(Span.dim("${location.name.uppercase()} · ${config.calculation.methodShortName(location).uppercase()}")),
    )

    /** Timeline rows. [state] marks next/now when the schedule is today's. */
    fun rows(s: DaySchedule, config: SalahConfig, state: PrayerClockState?): List<Line> {
        val d = config.display
        return Prayer.entries.map { p ->
            val label = Renderer.pad(s.label(p, d.jumuahRelabel), 11)
            val t = time(s.time(p), s.zone, d)
            var marker: Span? = null
            if (state != null) {
                if (state.nowPrayer == p) marker = Span.accent("   ◀ now")
                else if (state.next?.let { !it.isTomorrow && it.prayer == p } == true) marker = Span.accent("   ◀ next")
            }
            val isPast = state != null && (s.time(p)?.let { it <= state.now } ?: false)
            val kind = if (p == Prayer.SUNRISE || (isPast && marker == null)) Span.Kind.DIM else Span.Kind.NORMAL
            listOfNotNull(Span(label, kind), Span(t, if (marker != null) Span.Kind.BOLD else kind), marker)
        }
    }

    fun undefinedNote(s: DaySchedule): List<Line> {
        val (reason, suggestion) = s.undefinedExplanation ?: return emptyList()
        val lines = wrap(reason, 60).map { listOf(Span.accent(it)) }.toMutableList()
        if (s.times.isEmpty() && suggestion != null) lines += wrap(suggestion, 60).map { listOf(Span.dim(it)) }
        else if (s.times.isNotEmpty()) lines += listOf(Span.dim("Try: salah config set calculation.highLatitudeRule seventhOfTheNight"))
        return lines
    }

    /** Word-wraps to keep box lines readable. */
    fun wrap(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var cur = ""
        for (word in text.split(" ").filter { it.isNotEmpty() }) {
            if (cur.isNotEmpty() && cur.length + word.length + 1 > width) { lines += cur; cur = "" }
            cur += (if (cur.isEmpty()) "" else " ") + word
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }
}
