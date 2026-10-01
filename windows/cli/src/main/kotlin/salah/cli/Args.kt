package salah.cli

/** An error with a user-facing message and a specific exit code. */
class CliError(val code: Int, override val message: String) : Exception(message) {
    companion object {
        const val RUNTIME = 1
        const val USAGE = 2
        const val MISSING_CONFIG = 3

        fun usage(message: String) = CliError(USAGE, message)
        fun missingLocation() = CliError(MISSING_CONFIG, "No location set. Run `salah setup` or `salah location set <city>`.")
    }
}

/** Thrown for --help; carries the text to print and exits 0. */
class HelpRequested(val text: String) : Exception()

/**
 * Minimal argument parser in the spirit of swift-argument-parser: flags, options with values
 * (`--lat 1.3` or `--lat=1.3`, negative numbers allowed), and positionals.
 */
class Args(tokens: List<String>, private val flags: Set<String>, private val options: Set<String>, private val help: String) {
    val positionals = mutableListOf<String>()
    private val setFlags = mutableSetOf<String>()
    private val values = mutableMapOf<String, String>()

    init {
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-h" || t == "--help" -> throw HelpRequested(help)
                t == "--" -> { positionals += tokens.drop(i + 1); break }
                t.startsWith("--") -> {
                    val name = t.removePrefix("--").substringBefore("=")
                    val inline = if (t.contains("=")) t.substringAfter("=") else null
                    when (name) {
                        in flags -> {
                            if (inline != null) throw CliError.usage("Flag --$name doesn't take a value.")
                            setFlags += name
                        }
                        in options -> {
                            val v = inline ?: tokens.getOrNull(++i) ?: throw CliError.usage("Missing value for option '--$name'.")
                            values[name] = v
                        }
                        else -> throw CliError.usage("Unknown option '--$name'.\n\n$help")
                    }
                }
                t.startsWith("-") && t.length > 1 && t[1].isLetter() -> throw CliError.usage("Unknown option '$t'.\n\n$help")
                else -> positionals += t
            }
            i++
        }
    }

    fun flag(name: String) = name in setFlags
    fun option(name: String): String? = values[name]

    fun double(name: String): Double? = option(name)?.let {
        it.toDoubleOrNull() ?: throw CliError.usage("The value '$it' is invalid for '--$name'.")
    }

    /** Output flags shared by the read commands. */
    fun output(): OutputOptions {
        val chosen = listOf("json", "compact", "plain").count { flag(it) }
        if (chosen > 1) throw CliError.usage("Use only one of --json, --compact and --plain.")
        return OutputOptions(flag("json"), flag("compact"), flag("plain"), flag("no-color"))
    }

    fun noExtraPositionals(max: Int = 0) {
        if (positionals.size > max) throw CliError.usage("Unexpected argument '${positionals[max]}'.\n\n$help")
    }

    companion object {
        val OUTPUT_FLAGS = setOf("json", "compact", "plain", "no-color")
    }
}

data class OutputOptions(val json: Boolean, val compact: Boolean, val plain: Boolean, val noColor: Boolean) {
    val mode: OutputMode
        get() = when {
            json -> OutputMode.JSON
            compact -> OutputMode.COMPACT
            plain -> OutputMode.PLAIN
            else -> OutputMode.PRETTY
        }

    val renderer: Renderer get() = Renderer(mode, mode == OutputMode.PRETTY && !noColor && Terminal.colorAllowed())
}

object Help {
    const val OUTPUT = """
  --json                  Print JSON (stable schema, ISO 8601 times with offset).
  --compact               Print a single line, for shell prompts.
  --plain                 Print without box drawing or color.
  --no-color              Disable color."""

    val ROOT = """
OVERVIEW: Prayer times, countdowns and reminder settings from the terminal.

Shares its configuration with the Salah app. Reminders are delivered by the app;
the CLI only edits their settings.

Exit codes: 0 success, 1 runtime error, 2 invalid usage, 3 missing location or configuration.

USAGE: salah <subcommand>

OPTIONS:
  --version               Show the version.
  -h, --help              Show help information.

SUBCOMMANDS:
  today (default)         Show today's prayer times and the next prayer (the default command).
  next                    Show the next prayer and the countdown to it.
  schedule                Show prayer times for a day, a week or a month.
  location                Show or set the location used for prayer times.
  setup                   Interactive first-run setup: location, method, Asr and clock.
  config                  Read and edit the shared configuration.
  reminders               Show or change reminder settings. The Salah app delivers the notifications.

  See 'salah help <subcommand>' for detailed help.
""".trimStart()

    val TODAY = """
OVERVIEW: Show today's prayer times and the next prayer (the default command).

USAGE: salah today [--date <date>] [--json] [--compact] [--plain] [--no-color]

OPTIONS:
  --date <date>           Show another date instead of today (YYYY-MM-DD).$OUTPUT
  -h, --help              Show help information.
""".trimStart()

    val NEXT = """
OVERVIEW: Show the next prayer and the countdown to it.

USAGE: salah next [--watch] [--json] [--compact] [--plain] [--no-color]

OPTIONS:
  --watch                 Refresh every second until Ctrl-C.$OUTPUT
  -h, --help              Show help information.
""".trimStart()

    val SCHEDULE = """
OVERVIEW: Show prayer times for a day, a week or a month.

USAGE: salah schedule [--date <date>] [--week] [--month] [--json] [--compact] [--plain] [--no-color]

OPTIONS:
  --date <date>           Start date (YYYY-MM-DD). Defaults to today.
  --week                  Seven days starting at the date.
  --month                 The whole calendar month containing the date.$OUTPUT
  -h, --help              Show help information.
""".trimStart()

    val LOCATION = """
OVERVIEW: Show or set the location used for prayer times.

USAGE: salah location <subcommand>

SUBCOMMANDS:
  show (default)          Show the saved location.
  set                     Set the location by city search or coordinates.
""".trimStart()

    val LOCATION_SET = """
OVERVIEW: Set the location by city search or coordinates.

Examples:
  salah location set Singapore
  salah location set --lat -6.2088 --lon 106.8456 --tz Asia/Jakarta --name Jakarta

City search uses Open-Meteo's geocoding service, so the query is sent to Open-Meteo.

USAGE: salah location set [<query> ...] [--lat <lat>] [--lon <lon>] [--tz <tz>] [--name <name>] [--first]

OPTIONS:
  --lat <lat>             Latitude in degrees (-90 to 90).
  --lon <lon>             Longitude in degrees (-180 to 180).
  --tz <tz>               IANA time zone, e.g. Asia/Jakarta. Looked up from the coordinates if omitted.
  --name <name>           Display name when setting coordinates.
  --first                 Pick the first search result without asking.
  -h, --help              Show help information.
""".trimStart()

    val SETUP = """
OVERVIEW: Interactive first-run setup: location, method, Asr and clock.

USAGE: salah setup
""".trimStart()

    val CONFIG = """
OVERVIEW: Read and edit the shared configuration.

USAGE: salah config <subcommand>

SUBCOMMANDS:
  get (default)           Print one value, or every key and value.
  set                     Change one value.
  path                    Print the config file path.
  reset                   Restore defaults. Keeps the location unless --all is given.

Examples:
  salah config set calculation.method singapore
  salah config set calculation.offsets.isha 2
  salah config set reminders.asr.leadMinutes 15
  salah config set display.clock 12
""".trimStart()

    val REMINDERS = """
OVERVIEW: Show or change reminder settings. The Salah app delivers the notifications.

USAGE: salah reminders <subcommand>

SUBCOMMANDS:
  status (default)        Show reminder settings and whether the Salah app is running.
  enable                  Turn reminders on (all, or one prayer with --prayer). Also clears any pause.
  disable                 Turn reminders off (all, or one prayer with --prayer).
""".trimStart()
}
