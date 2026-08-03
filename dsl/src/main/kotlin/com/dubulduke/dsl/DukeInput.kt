package com.dubulduke.dsl

interface DukeInput {
    fun isClickDown(): Boolean
    fun isRightClickDown(): Boolean

    val pointerX: Double
    val pointerY: Double

    val scrollDistance: Double
}