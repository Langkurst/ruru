package io.github.langkurst.ruru.app.tools

/** The iPhone has no tone or mic check yet: the Tools page says so (Find) or leaves the tool out (Mic check). */
actual fun audioTools(): AudioTools = AudioTools(find = null, mic = null)
