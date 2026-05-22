package com.camcompiler.app

/**
 * Template-based query expansion for CLIP text matching.
 *
 * CLIP is sensitive to exact phrasing because it was trained on short image
 * captions. The same scene might match "a tunnel" much better than "going
 * through a tunnel", or vice versa. We try several phrasings of each query
 * and take the max similarity, which catches more true matches.
 *
 * No LLM is used here — just linguistic patterns. Generates 3-5 alternatives
 * in microseconds with zero memory cost.
 *
 * The bike-vlog vocabulary at the bottom is what makes this app-specific:
 * common queries from bike footage get specialized expansions.
 */
object QueryExpander {

    /**
     * Generate up to MAX_EXPANSIONS phrasings of the user's query.
     * The first one is always the original, unchanged.
     */
    fun expand(query: String): List<String> {
        val original = query.trim()
        if (original.isEmpty()) return emptyList()

        val expansions = LinkedHashSet<String>()  // preserves insertion order, dedupes
        expansions.add(original)

        val lower = original.lowercase()

        // === Pattern 1: "going through X" → variants ===
        Regex("""going through (.+)""").find(lower)?.let { m ->
            val noun = m.groupValues[1].trim()
            expansions.add("inside $noun")
            expansions.add("passing through $noun")
            expansions.add("$noun entrance")
        }

        // === Pattern 2: "X view" / "view of X" ===
        Regex("""view of (.+)""").find(lower)?.let { m ->
            val noun = m.groupValues[1].trim()
            expansions.add("$noun in the distance")
            expansions.add("a $noun scene")
        }

        // === Pattern 3: Add "a photo of" prefix (classic CLIP recipe) ===
        if (!lower.startsWith("a photo of") && !lower.startsWith("photo of")) {
            expansions.add("a photo of $lower")
        }

        // === Pattern 4: Add article if missing ===
        if (!lower.startsWith("a ") && !lower.startsWith("an ") &&
            !lower.startsWith("the ") && !lower.startsWith("photo")) {
            // pick "a" or "an" based on first letter of query
            val article = if (lower.firstOrNull() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
            expansions.add("$article $lower")
        }

        // === Pattern 5: Drop leading "the " or "a " for variety ===
        when {
            lower.startsWith("the ") -> expansions.add(lower.removePrefix("the "))
            lower.startsWith("a ") -> expansions.add(lower.removePrefix("a "))
            lower.startsWith("an ") -> expansions.add(lower.removePrefix("an "))
        }

        // === Pattern 6: Bike-vlog vocabulary expansions ===
        // Triggered when the original contains a keyword. Adds well-phrased
        // alternatives that CLIP tends to match well on.
        for ((keyword, alts) in BIKE_VLOG_VOCAB) {
            if (lower.contains(keyword)) {
                expansions.addAll(alts)
            }
        }

        // === Pattern 7: Action-y queries get a perspective-prefix ===
        if (containsAnyActionWord(lower)) {
            expansions.add("first-person view of $lower")
            expansions.add("riding $lower")
        }

        return expansions.toList().take(MAX_EXPANSIONS)
    }

    /**
     * Detect if a query is action-oriented. Used by the detector to auto-boost
     * the motion-MAD weight (since CLIP doesn't see motion well).
     */
    fun isActionQuery(query: String): Boolean {
        return containsAnyActionWord(query.lowercase())
    }

    private fun containsAnyActionWord(lowerQuery: String): Boolean {
        return ACTION_WORDS.any { lowerQuery.contains(it) }
    }

    private const val MAX_EXPANSIONS = 5

    /**
     * Words that suggest the user is asking about motion/action rather than
     * static visual content. When any of these appears, the detector gives
     * more weight to the motion-MAD signal.
     */
    private val ACTION_WORDS = setOf(
        "fast", "speed", "descent", "descending", "downhill", "uphill",
        "turn", "swerve", "drop", "rush", "zoom", "rapid", "quick",
        "sprint", "race", "accelerat", "brake", "skid", "jump"
    )

    /**
     * Bike-vlog-specific vocabulary. Each entry maps a trigger keyword to
     * a list of well-phrased alternatives that match the same visual concept.
     */
    private val BIKE_VLOG_VOCAB: Map<String, List<String>> = mapOf(
        "tunnel" to listOf(
            "inside a tunnel",
            "tunnel with lights",
            "dark tunnel",
            "underground passage",
        ),
        "bridge" to listOf(
            "crossing a bridge",
            "view from a bridge",
            "long bridge",
        ),
        "viewpoint" to listOf(
            "scenic view",
            "panoramic landscape",
            "mountain overlook",
        ),
        "mountain" to listOf(
            "mountains in the distance",
            "mountain range",
            "tall mountain peak",
        ),
        "forest" to listOf(
            "trees on both sides",
            "wooded area",
            "trees lining the road",
        ),
        "city" to listOf(
            "urban street",
            "buildings on both sides",
            "city traffic",
        ),
        "highway" to listOf(
            "open highway",
            "multi-lane road",
            "freeway driving",
        ),
        "downhill" to listOf(
            "descending road",
            "going down a hill",
            "steep descent",
        ),
        "uphill" to listOf(
            "climbing a hill",
            "ascending road",
            "steep uphill",
        ),
        "sunset" to listOf(
            "golden hour",
            "warm evening light",
            "sun setting on horizon",
        ),
        "sunrise" to listOf(
            "early morning light",
            "sun rising",
            "dawn",
        ),
        "rain" to listOf(
            "wet road",
            "rainy weather",
            "raindrops",
        ),
        "cyclist" to listOf(
            "another bike rider",
            "person on a bicycle",
            "fellow cyclist",
        ),
        "car" to listOf(
            "passing car",
            "vehicle on the road",
            "automobile in view",
        ),
        "lake" to listOf(
            "body of water",
            "water in the distance",
            "lakeside view",
        ),
        "field" to listOf(
            "open countryside",
            "rural landscape",
            "farmland view",
        ),
    )
}
