package dev.sebastian.vozlocal.moonshine

import android.content.Context
import java.io.File

data class MoonshineAsset(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String
)

data class MoonshineModelSpec(
    val id: String,
    val sizeBytes: Long,
    val assets: List<MoonshineAsset>
)

/** The exact, pinned Moonshine Spanish bundles shipped by the opt-in catalog. */
object MoonshineModels {
    private const val ROOT = "https://download.moonshine.ai/model"
    private fun asset(model: String, name: String, size: Long, sha: String) = MoonshineAsset(
        name, size, sha, "$ROOT/$model-streaming-es/quantized_26_08_24/$name"
    )

    private val tinyAssets = listOf(
        asset("tiny", "adapter.ort", 1318472, "2efd97cd2ee7d89578dd1d429a849be9fe37ac81dd9c4e1d31c8a2e7f85d43e6"),
        asset("tiny", "cross_kv.ort", 1288120, "9e268b1b3bb0f9ff79bca6e76055d41298de0a5ba52505ee04907a2642efa3ae"),
        asset("tiny", "decoder_kv.ort", 19717336, "7adf56b982df98e385d71823b9a40cf9296ae3a746efcf03f3d7bb4604032db0"),
        asset("tiny", "encoder.ort", 7772792, "ec9eb43a01fbfe242a2c6a86050cffe4a31050e1c9f084e98560d6c100004505"),
        asset("tiny", "frontend.model.ort", 23176, "9d19434b861de0c1d2aff52556911ae565cf3212ebe6d7f797c76c5c5066c2fc"),
        asset("tiny", "frontend.weights.ort", 2093280, "5e4d5471cd5d984d4b94a603d861539ebf1dc72de11b0cf13f38316725d5ce48"),
        asset("tiny", "streaming_config.json", 509, "d31afd7c2dd5ee9ae3fa232312c5b42cb3e4fe1c6e9c53d885f2bea63023790a"),
        asset("tiny", "tokenizer.bin", 102888, "5fbb7d4314dcb18e03c5f975609e4a4cd572b22b01d2b2accb1b3e6830696f36")
    )
    private val smallAssets = listOf(
        asset("small", "adapter.ort", 2869296, "04b54114c8aab534222922640f7ca0882948ff9f6ed76777f6e184e55a8e8b15"),
        asset("small", "cross_kv.ort", 5358752, "4bfd0a641d72ccdae22751f86bbb2e25ff70c1fba4f81213f2415a1accff9618"),
        asset("small", "decoder_kv.ort", 61314512, "5b77c3d6baf801ef925a5bc54d7eb3db0c35ebbb86f8eaf5390d7f5bb42fef37"),
        asset("small", "encoder.ort", 44358376, "a9b8d6d5d9348d0e319cceffdb0196ef622df8e4e9f3fb4797dcd8dfafd50857"),
        asset("small", "frontend.model.ort", 26776, "69c76287f49db365aa278d4908ec450e69ca1b4bfb7e836159d963d623ee0c13"),
        asset("small", "frontend.weights.ort", 7769280, "2f5a0eb5f3004c9447810d74274a352746319a32144e31d95ef410f425b84dda"),
        asset("small", "streaming_config.json", 512, "12d16c7f5ea6734d197b79baf47914ea7e996d6fca8d303a19aca10d0617cecc"),
        asset("small", "tokenizer.bin", 102888, "5fbb7d4314dcb18e03c5f975609e4a4cd572b22b01d2b2accb1b3e6830696f36")
    )
    private val specs = mapOf(
        "moonshine_tiny_es" to MoonshineModelSpec("moonshine_tiny_es", tinyAssets.sumOf { it.sizeBytes }, tinyAssets),
        "moonshine_small_es" to MoonshineModelSpec("moonshine_small_es", smallAssets.sumOf { it.sizeBytes }, smallAssets)
    )

    fun isMoonshine(id: String): Boolean = specs.containsKey(id)
    fun model(id: String): MoonshineModelSpec? = specs[id]
    fun directory(context: Context, id: String): File = File(File(context.filesDir, "moonshine"), id)
    fun isDownloaded(context: Context, id: String): Boolean = MoonshineModelDownloader(context).verifiedDirectory(id) != null
}
