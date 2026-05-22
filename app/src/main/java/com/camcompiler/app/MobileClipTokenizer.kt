package com.camcompiler.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * CLIP byte-pair encoding (BPE) tokenizer.
 *
 * CLIP doesn't use WordPiece or SentencePiece — it uses a custom BPE built on
 * byte sequences. Each character is first mapped to a UTF-8-like alphabet, then
 * BPE merges are applied to produce the final token IDs.
 *
 * Required asset files (placed in app/src/main/assets/):
 *   - clip_vocab.json  — maps token strings to integer IDs (~49k entries)
 *   - clip_merges.txt  — ordered list of "pair1 pair2" merge rules (~48k lines)
 *
 * Output: int array of token IDs, padded/truncated to MAX_TOKENS (77), with
 * <|startoftext|> (49406) prepended and <|endoftext|> (49407) appended.
 *
 * This is a direct port of OpenAI's CLIP tokenizer (simple_tokenizer.py).
 * https://github.com/openai/CLIP/blob/main/clip/simple_tokenizer.py
 */
class MobileClipTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    private val byteEncoder: Map<Int, Char>,
) {
    /**
     * Tokenize text into an int array of length MAX_TOKENS, padded with 0 (the
     * model's pad token) and bracketed with start/end-of-text markers.
     */
    fun encode(text: String): IntArray {
        val cleaned = cleanText(text)
        val tokens = mutableListOf<Int>()
        tokens.add(SOT_TOKEN)
        for (rawToken in basicTokenize(cleaned)) {
            // Map each byte of the token to its corresponding character via byteEncoder
            val mapped = rawToken.toByteArray(Charsets.UTF_8).joinToString("") {
                byteEncoder[it.toInt() and 0xFF].toString()
            }
            for (bpeToken in bpe(mapped).split(' ')) {
                val id = encoder[bpeToken]
                if (id != null) {
                    tokens.add(id)
                    if (tokens.size >= MAX_TOKENS - 1) break  // leave room for EOT
                }
            }
            if (tokens.size >= MAX_TOKENS - 1) break
        }
        tokens.add(EOT_TOKEN)

        // Pad to MAX_TOKENS with 0
        val result = IntArray(MAX_TOKENS)
        for (i in tokens.indices) {
            if (i >= MAX_TOKENS) break
            result[i] = tokens[i]
        }
        return result
    }

    /**
     * Apply BPE merges to a single pre-byte-encoded "word".
     * Implements the standard BPE algorithm: repeatedly merge the highest-priority pair
     * until no more merges apply.
     */
    private fun bpe(token: String): String {
        if (token.isEmpty()) return token
        // Initial character split, with </w> on the last character (CLIP-specific)
        var word = token.map { it.toString() }.toMutableList()
        if (word.isNotEmpty()) {
            word[word.size - 1] = word[word.size - 1] + "</w>"
        }

        while (true) {
            val pairs = mutableListOf<Pair<String, String>>()
            for (i in 0 until word.size - 1) {
                pairs.add(word[i] to word[i + 1])
            }
            if (pairs.isEmpty()) break

            // Find the pair with the lowest rank (= highest priority)
            var bestPair: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (p in pairs) {
                val rank = bpeRanks[p] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestPair = p
                }
            }
            if (bestPair == null) break

            // Merge all occurrences of bestPair in word
            val (first, second) = bestPair
            val newWord = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                val idx = word.subList(i, word.size).indexOfFirst { it == first }
                if (idx == -1 || i + idx + 1 >= word.size || word[i + idx + 1] != second) {
                    newWord.addAll(word.subList(i, word.size))
                    break
                }
                newWord.addAll(word.subList(i, i + idx))
                newWord.add(first + second)
                i += idx + 2
            }
            word = newWord
            if (word.size == 1) break
        }
        return word.joinToString(" ")
    }

    /**
     * Basic tokenization: lowercase, normalize whitespace, split on punctuation +
     * whitespace boundaries.
     */
    private fun basicTokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        // Same regex as CLIP's tokenizer
        val pattern = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+"""
        )
        for (match in pattern.findAll(text.lowercase())) {
            tokens.add(match.value)
        }
        return tokens
    }

    /** Light cleaning of input text — collapse whitespace, strip leading/trailing. */
    private fun cleanText(text: String): String {
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    companion object {
        private const val TAG = "MobileClipTokenizer"

        /** Maximum sequence length for CLIP text encoder. */
        const val MAX_TOKENS = 77

        /** <|startoftext|> token ID (standard CLIP). */
        const val SOT_TOKEN = 49406

        /** <|endoftext|> token ID (standard CLIP). */
        const val EOT_TOKEN = 49407

        @Volatile
        private var INSTANCE: MobileClipTokenizer? = null

        /**
         * Load and cache the tokenizer. The vocab + merges files are loaded once
         * and reused. ~1.5 MB total memory once loaded.
         */
        fun getInstance(ctx: Context): MobileClipTokenizer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: load(ctx).also { INSTANCE = it }
            }
        }

        private fun load(ctx: Context): MobileClipTokenizer {
            Log.d(TAG, "Loading CLIP tokenizer assets...")
            val vocab = loadVocab(ctx)
            val merges = loadMerges(ctx)
            val byteEnc = buildByteEncoder()
            Log.d(TAG, "Loaded ${vocab.size} vocab entries, ${merges.size} merge rules")
            return MobileClipTokenizer(vocab, merges, byteEnc)
        }

        private fun loadVocab(ctx: Context): Map<String, Int> {
            val text = ctx.assets.open("clip_vocab.json").bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val map = HashMap<String, Int>(json.length())
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.getInt(k)
            }
            return map
        }

        private fun loadMerges(ctx: Context): Map<Pair<String, String>, Int> {
            val map = HashMap<Pair<String, String>, Int>()
            BufferedReader(InputStreamReader(ctx.assets.open("clip_merges.txt"))).use { reader ->
                var line = reader.readLine()
                var rank = 0
                // First line is a version comment — skip
                if (line != null && line.startsWith("#")) line = reader.readLine()
                while (line != null) {
                    val parts = line.split(' ')
                    if (parts.size == 2) {
                        map[parts[0] to parts[1]] = rank
                        rank++
                    }
                    line = reader.readLine()
                }
            }
            return map
        }

        /**
         * Build the byte-to-unicode mapping used by CLIP.
         * Maps each of the 256 byte values to a "printable" unicode character so that
         * BPE can operate on text strings without losing information.
         *
         * This is the same algorithm as CLIP's bytes_to_unicode() in simple_tokenizer.py.
         */
        private fun buildByteEncoder(): Map<Int, Char> {
            val bs = mutableListOf<Int>()
            // Printable ASCII range '!' to '~'
            for (b in 33..126) bs.add(b)
            // Latin-1 supplement ranges '¡' to '¬' and '®' to 'ÿ'
            for (b in 161..172) bs.add(b)
            for (b in 174..255) bs.add(b)

            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val map = HashMap<Int, Char>(256)
            for (i in bs.indices) {
                map[bs[i]] = cs[i].toChar()
            }
            return map
        }
    }
}
