package com.dubulduke.layout

import com.dubulduke.reactivity.NodeNames

/**
 * Run [body] with graph node names on, whatever the build is configured for.
 *
 * A handful of tests assert on the names themselves — `app.panel.[0].h.origin`, the two slots a
 * cycle report names, the derivation strings in the JSON dump. Those are specifications of the
 * *diagnostics*, so under `-Dduke.nodeNames=false` they are asserting about something the build has
 * deliberately removed, and they have to say so rather than fail.
 *
 * The distinction is worth keeping sharp, because it is the whole argument for `NodeNames` being a
 * switch at all: a test that needs this wrapper is testing a message. Every other test in the suite
 * passes with names off, which is what says the release build is not quietly relying on them.
 */
internal inline fun withNodeNames(body: () -> Unit) {
    val was = NodeNames.enabled
    NodeNames.enabled = true
    try {
        body()
    } finally {
        NodeNames.enabled = was
    }
}
