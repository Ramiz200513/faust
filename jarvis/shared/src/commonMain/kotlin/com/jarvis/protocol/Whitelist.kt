package com.jarvis.protocol

/**
 * The security core in one place: only commands whose [Command.name] is in
 * [allowed] may ever reach the agent. The relay runs [check] on every
 * command *after* the LLM proposes it and *before* forwarding — so even if a
 * prompt injection tricks the model into emitting shutdown, the whitelist
 * stops it. This is the object your thesis's "threat model" chapter refers to.
 */
class CommandWhitelist(
    private val allowed: Set<String>,
) {
    fun isAllowed(command: Command): Boolean = command.name in allowed

    /** Returns the command if allowed, or null to reject with DENIED. */
    fun check(command: Command): Command? =
        if (isAllowed(command)) command else null

    companion object {
        /** A safe starting set — no raw shutdown until you decide to enable it. */
        val DEFAULT = CommandWhitelist(
            setOf(
                "set_volume",
                "lock_screen",
                "list_processes",
                "kill_process",
                "open_url",
                "open_app",
                "screenshot",
                "agent_task",
                "click",
                "type_text",
                "scroll",
                "quick_action",
                "sequence",
                "dev_repos",
                "correct_task",
                "photo_task",
                "get_task_report",
                // "shutdown" and "reboot" left OUT on purpose — opt in explicitly.
            )
        )
    }
}
