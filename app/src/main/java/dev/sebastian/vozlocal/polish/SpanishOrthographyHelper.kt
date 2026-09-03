package dev.sebastian.vozlocal.polish

import java.util.Locale

/**
 * Spanish orthography helper that restores required acute accents (tildes)
 * on words that are frequently emitted unaccented by Whisper due to unaccented
 * web training data.
 *
 * Targets words that:
 * 1. Have no valid unaccented homograph in Spanish (e.g. 'así', 'malecón', 'también', 'después').
 * 2. Mandatory oxytones ending in '-ción' or '-sión' (e.g. 'canción', 'estación', 'atención').
 * 3. Interrogatives inside Spanish question clauses (e.g. '¿qué', '¿cómo', '¿dónde').
 */
object SpanishOrthographyHelper {

    // Words that NEVER exist without an accent in standard Spanish
    private val MANDATORY_ACCENTS = mapOf(
        "asi" to "así",
        "malecon" to "malecón",
        "tambien" to "también",
        "ademas" to "además",
        "despues" to "después",
        "aqui" to "aquí",
        "alli" to "allí",
        "alla" to "allá",
        "aca" to "acá",
        "quizas" to "quizás",
        "algun" to "algún",
        "ningun" to "ningún",
        "comun" to "común",
        "recien" to "recién",
        "almacen" to "almacén",
        "vaiven" to "vaivén",
        "estara" to "estará",
        "estaran" to "estarán",
        "estaras" to "estarás",
        "estaria" to "estaría",
        "estarian" to "estarían",
        "habra" to "habrá",
        "habran" to "habrán",
        "habria" to "habría",
        "habrian" to "habrían",
        "sera" to "será",
        "seran" to "serán",
        "seras" to "serás",
        "facil" to "fácil",
        "faciles" to "fáciles",
        "dificil" to "difícil",
        "dificiles" to "difíciles",
        "util" to "útil",
        "utiles" to "útiles",
        "inutil" to "inútil",
        "inutiles" to "inútiles",
        "rapido" to "rápido",
        "rapida" to "rápida",
        "rapidos" to "rápidos",
        "rapidas" to "rápidas",
        "ultimo" to "último",
        "ultima" to "última",
        "ultimos" to "últimos",
        "ultimas" to "últimas",
        "proximo" to "próximo",
        "proxima" to "próxima",
        "proximos" to "próximos",
        "proximas" to "próximas",
        "numero" to "número",
        "numeros" to "números",
        "pagina" to "página",
        "paginas" to "páginas",
        "musica" to "música",
        "telefono" to "teléfono",
        "telefonos" to "teléfonos",
        "articulo" to "artículo",
        "articulos" to "artículos",
        "vehiculo" to "vehículo",
        "vehiculos" to "vehículos",
        "capitulo" to "capítulo",
        "capitulos" to "capítulos",
        "politica" to "política",
        "politico" to "político",
        "politicos" to "políticos",
        "politicas" to "políticas",
        "economico" to "económico",
        "economica" to "económica",
        "automatico" to "automático",
        "automatica" to "automática",
        "publico" to "público",
        "callejon" to "callejón",
        "corazon" to "corazón",
        "rincon" to "rincón",
        "tiburon" to "tiburón",
        "carbon" to "carbón",
        "vagon" to "vagón",
        "salon" to "salón",
        "jabon" to "jabón",
        "cajon" to "cajón",
        "boton" to "botón",
        "balon" to "balón",
        "patron" to "patrón",
        "baston" to "bastón",
        "tacon" to "tacón",
        "sillon" to "sillón",
        "fogon" to "fogón",
        "talon" to "talón",
        "raton" to "ratón",
        "campeon" to "campeón",
        "avion" to "avión",
        "camion" to "camión",
        "limon" to "limón",
        "jamon" to "jamón",
        "perdon" to "perdón",
        "melon" to "melón",
        "salmon" to "salmón",
        "pasion" to "pasión",
        "mision" to "misión",
        "sesion" to "sesión",
        "vision" to "visión",
        "ilusion" to "ilusión",
        "conclusion" to "conclusión",
        "reunion" to "reunión",
        "opinion" to "opinión",
        "religion" to "religión",
        "region" to "región"
    )

    private val WORD_REGEXES = MANDATORY_ACCENTS.map { (unaccented, accented) ->
        Regex("(?i)\\b${Regex.escape(unaccented)}\\b") to accented
    }

    // Matches any unaccented word of 4+ characters ending in 'cion' or 'sion'
    // e.g. "atencion" -> "atención", "cancion" -> "canción"
    private val CION_SION_REGEX = Regex("(?i)\\b(\\p{L}{2,})(cion|sion)\\b")

    // Matches unaccented question words directly after opening question mark '¿'
    private val QUESTION_WORDS = mapOf(
        "que" to "qué",
        "como" to "cómo",
        "cuando" to "cuándo",
        "donde" to "dónde",
        "quien" to "quién",
        "quienes" to "quiénes",
        "cual" to "cuál",
        "cuales" to "cuáles",
        "cuanto" to "cuánto",
        "cuanta" to "cuánta",
        "cuantos" to "cuántos",
        "cuantas" to "cuántas",
        "por que" to "por qué"
    )

    private val QUESTION_REGEXES = QUESTION_WORDS.map { (unaccented, accented) ->
        Regex("(?i)(¿\\s*)${Regex.escape(unaccented)}\\b") to accented
    }

    fun fixAccents(text: String): String {
        if (text.isBlank()) return text
        var result = text

        // 1. Mandatory dictionary word replacements with case preservation
        for ((regex, accented) in WORD_REGEXES) {
            result = regex.replace(result) { match ->
                matchCase(match.value, accented)
            }
        }

        // 2. Generic -ción / -sión rule
        result = CION_SION_REGEX.replace(result) { match ->
            val prefix = match.groupValues[1]
            val suffix = match.groupValues[2]
            val accentedSuffix = if (suffix.startsWith("c", ignoreCase = true)) "ción" else "sión"
            matchCase(match.value, prefix + accentedSuffix)
        }

        // 3. Question words after ¿
        for ((regex, accented) in QUESTION_REGEXES) {
            result = regex.replace(result) { match ->
                val opening = match.groupValues[1]
                val matchedWord = match.value.removePrefix(opening)
                opening + matchCase(matchedWord, accented)
            }
        }

        return result
    }

    private fun matchCase(source: String, target: String): String {
        return when {
            source.all { it.isUpperCase() } -> target.uppercase(Locale.getDefault())
            source.firstOrNull()?.isUpperCase() == true -> target.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
            else -> target.lowercase(Locale.getDefault())
        }
    }
}
