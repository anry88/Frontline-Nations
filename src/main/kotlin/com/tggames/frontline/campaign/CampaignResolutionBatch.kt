package com.tggames.frontline.campaign

internal class CampaignResolutionBatch(
    private val inTransaction: (() -> Unit) -> Unit,
) {
    fun <Input, Result> resolve(
        inputs: Iterable<Input>,
        simulate: (Input) -> Result,
        persist: (Input, Result) -> Unit,
    ) {
        inputs.forEach { input ->
            val result = simulate(input)
            inTransaction { persist(input, result) }
        }
    }
}
