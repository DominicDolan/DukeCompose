package com.dubulduke.reactivity

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeGraphTest {
    @Test
    fun graphRunsOnItsOwnerThread() {
        val graph = Graph()
        val input = graph.input(3.0)
        val doubled = graph.derive { read(input) * 2.0 }
        assertEquals(6.0, graph.read(doubled))
        graph.setInput(input, 4.0)
        assertEquals(8.0, graph.read(doubled))
    }

    @Test
    fun declaredDepthAppliesToNewGraphs() {
        ChainDepth.declareBudget(700)
        try { assertEquals(700, Graph().maxChainDepth) }
        finally { ChainDepth.declareBudget(ChainDepth.DEFAULT_MAX_CHAIN_DEPTH) }
    }
}
