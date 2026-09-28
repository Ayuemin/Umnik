package com.ayuemin.ymnik.diagnostics

/** Process-wide telemetry for the single active Local Shell run. JVM-test safe. */
internal object LocalShellBudgetTelemetry {
    data class Snapshot(
        val turns: Int,
        val tools: Int,
        val input: Long,
        val output: Long,
        val cached: Long,
        val cost: Double,
        val repeatSig: Int,
        val noNew: Int,
        val wallMs: Long
    ) {
        fun line(): String =
            "turns=$turns; tools=$tools; input=$input; output=$output; cache=$cached; " +
                "cost=${if (cost > 0.0) cost.toString() else "unknown"}; repeat_sig=$repeatSig; no_new=$noNew; wall=${wallMs}ms"
    }

    private var startedAt = System.currentTimeMillis()
    private var turns = 0
    private var tools = 0
    private var input = 0L
    private var output = 0L
    private var cached = 0L
    private var cost = 0.0
    private var repeatSig = 0
    private var noNew = 0
    private var softBudgetReminderSent = false

    @Synchronized
    fun reset() {
        startedAt = System.currentTimeMillis()
        turns = 0
        tools = 0
        input = 0L
        output = 0L
        cached = 0L
        cost = 0.0
        repeatSig = 0
        noNew = 0
        softBudgetReminderSent = false
    }

    @Synchronized
    fun recordLlm(turn: Int?, inputTokens: Long?, outputTokens: Long?, cachedTokens: Long?, costUsd: Double?) {
        if (turn != null) turns = maxOf(turns, turn)
        if (inputTokens != null && inputTokens > 0) input += inputTokens
        if (outputTokens != null && outputTokens > 0) output += outputTokens
        if (cachedTokens != null && cachedTokens > 0) cached += cachedTokens
        if (costUsd != null && costUsd > 0.0) cost += costUsd
    }

    @Synchronized
    fun recordTool(noNewResult: Boolean, repeatCount: Int?) {
        tools += 1
        if (noNewResult) noNew += 1
        if (repeatCount != null) repeatSig = maxOf(repeatSig, repeatCount)
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        turns = turns,
        tools = tools,
        input = input,
        output = output,
        cached = cached,
        cost = cost,
        repeatSig = repeatSig,
        noNew = noNew,
        wallMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
    )

    @Synchronized
    fun consumeSoftBudgetReminder(): String? {
        if (softBudgetReminderSent) return null
        val due = input >= SOFT_INPUT_TOKENS || cost >= SOFT_COST_USD
        if (!due) return null
        softBudgetReminderSent = true
        return "SOFT_BUDGET_CHECKPOINT: ${snapshot().line()}. Не останавливай полезную работу только из-за бюджета, " +
            "но не расширяй scope: используй уже собранные доказательства, не перечитывай неизменённые файлы и выбери один следующий шаг к критерию готовности."
    }

    private const val SOFT_INPUT_TOKENS = 500_000L
    private const val SOFT_COST_USD = 0.15
}
