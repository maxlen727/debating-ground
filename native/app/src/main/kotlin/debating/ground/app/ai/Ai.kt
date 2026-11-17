package debating.ground.app.ai

import debating.ground.app.data.Config
import debating.ground.app.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

// AI 调用的统一入口，和网页版 src/lib/ai.ts 的 task()、runAI() 一样：
// 同一时间只有一个请求；失败时只给出原因，不改变讨论进度，不换成演示模板、不编造回复（约定 2）。

/** 一次调用的结果。 */
sealed interface AiOutcome {
    /** [source] 是 "api" 或 "demo"；演示模式会推进 [demoStep]，调用方写回讨论。 */
    data class Success(val result: JsonObject, val source: String, val demoStep: Int?) : AiOutcome

    /** 给用户看的原因。流式显示过的半截文字，调用方要撤回。 */
    data class Failed(val message: String) : AiOutcome

    /** 已经有请求在进行，这次没有发出（和网页版一样直接忽略）。 */
    data object Busy : AiOutcome
}

/** 输出给人读的文字的用途，可以边生成边显示；和 shared/purposes.ts 的 STREAMABLE 一致。 */
val STREAMABLE = setOf("discuss", "debate", "hint", "explain", "reflect")

class Ai(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    /** 演示模式逐字出现的节奏；测试里传 0 跳过。 */
    private val demoTypingMs: Long = 40,
    private val timeoutMs: Long = 100_000,
    private val remoteFor: (Config) -> RemoteBackend = { RemoteBackend(it.remote.url, it.remote.password) },
) {
    private val current = AtomicReference<Deferred<JsonObject>?>(null)
    private var cancelled = false

    val busy get() = current.get() != null

    /** 取消正在进行的请求：讨论进度不变，显示“已取消”。 */
    fun cancel() {
        cancelled = true
        current.get()?.cancel()
    }

    /**
     * 调用一次 AI。[onText] 不为空、并且这种用途能流式显示时，每收到新文字回调一次到目前为止的全文。
     * 演示模式按 [session] 生成，并返回推进后的 demoStep。
     */
    suspend fun run(purpose: String, payload: JsonObject, config: Config, session: Session?, onText: ((String) -> Unit)? = null): AiOutcome {
        val live = onText?.takeIf { purpose in STREAMABLE }
        var demoStep: Int? = null
        val source = if (config.aiMode == "demo") "demo" else "api"
        when (config.aiMode) {
            "remote" -> if (config.remote.url.isEmpty()) {
                return AiOutcome.Failed("还没有连接你的后端。请在“设置”里填写后端地址和访问口令，或切换到演示模式。")
            }

            "demo" -> Unit

            // 直接连接服务商在 M5 才有：明确告诉用户，不悄悄换成演示
            else -> return AiOutcome.Failed("这个版本还不能直接连接服务商。请在“设置”里改用自己部署的后端，或切换到演示模式。")
        }
        val job = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            withTimeout(timeoutMs) {
                if (config.aiMode == "remote") {
                    remoteFor(config).generate(purpose, payload, live)
                } else {
                    val out = demoRun(purpose, payload, session)
                    demoStep = out.demoStep
                    val result = out.result as? JsonObject ?: throw AiFailure("演示模式没有这一步的内容。")
                    val text = out.result.textOrNull()
                    // 演示模式也模拟逐字生成
                    if (live != null && text != null && demoTypingMs > 0) {
                        var i = 0
                        while (i < text.length) {
                            i = minOf(text.length, i + 2 + Random.nextInt(4))
                            live(text.substring(0, i))
                            delay(demoTypingMs)
                        }
                    }
                    result
                }
            }
        }
        if (!current.compareAndSet(null, job)) {
            job.cancel()
            return AiOutcome.Busy
        }
        cancelled = false
        return try {
            AiOutcome.Success(job.await(), source, demoStep)
        } catch (e: TimeoutCancellationException) {
            AiOutcome.Failed("请求超时，当前进度没有改变。")
        } catch (e: CancellationException) {
            // 调用方自己被取消（比如页面关了）：照常往上抛；用户点了取消：显示原因
            if (!currentCoroutineContext().isActive) throw e
            AiOutcome.Failed(if (cancelled) "已取消，当前进度没有改变。" else "请求被中断，当前进度没有改变。")
        } catch (e: AiFailure) {
            AiOutcome.Failed(e.message ?: "请求失败。")
        } catch (e: DemoException) {
            AiOutcome.Failed(e.message ?: "请求失败。")
        } catch (e: Exception) {
            AiOutcome.Failed("请求失败，请稍后重试。")
        } finally {
            // 调用方被取消时，后台的请求也停下，不白白跑完
            if (!job.isCompleted) job.cancel()
            current.set(null)
        }
    }
}
