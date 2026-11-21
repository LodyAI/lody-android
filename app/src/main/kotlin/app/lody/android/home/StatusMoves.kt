package app.lody.android.home

import app.lody.android.runtime.StatusTier
import app.lody.android.runtime.StatusViewCommand
import app.lody.android.runtime.StatusViewTarget

/**
 * One reorderable position in the Home list: a section header or a family (a root row; its opened
 * children render inside it and move with it). Keys never name the section, so a row keeps its
 * key while it is dragged across sections.
 */
sealed interface ListSlot {
    val key: String

    data class Header(val section: HomeSection) : ListSlot {
        override val key get() = "header/${section.id}"
    }

    data class Row(val sectionId: String, val chat: ChatItem) : ListSlot {
        override val key get() = rowKey(chat.id)
    }

    companion object {
        fun rowKey(id: String) = "row/$id"
    }
}

/**
 * Translates drags, menu items and accessibility actions into the shared planner's commands. A
 * move names the neighbour it lands in front of (null is the end), never an index or order key,
 * so the runtime resolves it against its current order. Native code never orders rows itself.
 */
object StatusMoves {
    /** Header and root slots, in display order. */
    fun slots(sections: List<HomeSection>, settledExpanded: Boolean = false): List<ListSlot> = sections.flatMap { section ->
        listOf(ListSlot.Header(section)) + section.visibleChats(settledExpanded).filter { it.nestedUnder == null }.map { ListSlot.Row(section.id, it) }
    }

    /** The area a section stands for, if rows can be placed there by hand. */
    fun target(section: HomeSection): StatusViewTarget? = when (val header = section.header) {
        is HomeSection.Header.Pinned -> StatusViewTarget.Pinned(header.sectionId)
        is HomeSection.Header.Tier -> StatusViewTarget.Focus.takeIf { header.tier == StatusTier.Focus }
        else -> null
    }

    fun allowed(chat: ChatItem, target: StatusViewTarget): Boolean = when (target) {
        is StatusViewTarget.Pinned -> chat.canPrefer
        StatusViewTarget.Focus -> chat.canManage
    }

    /** Moves the [from] slot to [to]'s position; the list a reorderable `onMove` must apply at once. */
    fun swap(slots: List<ListSlot>, from: String, to: String): List<ListSlot> {
        val source = slots.indexOfFirst { it.key == from }
        val destination = slots.indexOfFirst { it.key == to }
        if (source < 0 || destination < 0 || source == destination || slots[source] !is ListSlot.Row) return slots
        return slots.toMutableList().apply { add(destination, removeAt(source)) }
    }

    /**
     * The command a finished drag stands for, or null when it changes nothing or lands where rows
     * cannot be placed (time and project groups, Later, Settled) or the row may not go.
     */
    fun drop(slots: List<ListSlot>, original: List<ListSlot>, id: String): StatusViewCommand.Move? {
        val placed = place(slots, id) ?: return null
        val (section, before) = placed
        val chat = (slots.first { it.key == ListSlot.rowKey(id) } as ListSlot.Row).chat
        val target = target(section) ?: return null
        if (!allowed(chat, target)) return null
        val from = place(original, id)
        if (from != null && from.first.id == section.id && from.second == before) return null
        return StatusViewCommand.Move(id, target, before)
    }

    /** The section a row sits in and the root it sits in front of. */
    private fun place(slots: List<ListSlot>, id: String): Pair<HomeSection, String?>? {
        val index = slots.indexOfFirst { it.key == ListSlot.rowKey(id) }
        if (index < 0) return null
        val header = slots.subList(0, index).lastOrNull { it is ListSlot.Header } as? ListSlot.Header ?: return null
        val next = slots.drop(index + 1).firstOrNull()
        return header.section to (next as? ListSlot.Row)?.chat?.id
    }

    private fun roots(section: HomeSection) = section.chats.filter { it.nestedUnder == null }

    /** "Move to top" of the row's own manually ordered area; null when already first or not ordered. */
    fun toTop(section: HomeSection, chat: ChatItem): StatusViewCommand.Move? {
        val target = target(section)?.takeIf { allowed(chat, it) } ?: return null
        val first = roots(section).firstOrNull() ?: return null
        if (first.id == chat.id) return null
        return StatusViewCommand.Move(chat.id, target, first.id)
    }

    /** Accessibility Move up (-1) / Move down (+1) within the row's manually ordered area. */
    fun by(section: HomeSection, chat: ChatItem, delta: Int): StatusViewCommand.Move? {
        val target = target(section)?.takeIf { allowed(chat, it) } ?: return null
        val roots = roots(section)
        val index = roots.indexOfFirst { it.id == chat.id }
        val next = index + delta
        if (index < 0 || next < 0 || next >= roots.size) return null
        val rest = roots.filterNot { it.id == chat.id }
        return StatusViewCommand.Move(chat.id, target, rest.getOrNull(next)?.id)
    }

    /** "Move to section": the top of that pin section (pins an unpinned row). */
    fun toSection(sections: List<HomeSection>, chat: ChatItem, sectionId: String?): StatusViewCommand.Move? {
        if (!chat.canPrefer) return null
        val section = sections.firstOrNull { (it.header as? HomeSection.Header.Pinned)?.let { header -> header.sectionId == sectionId } == true }
        val first = section?.let(::roots)?.firstOrNull { it.id != chat.id }
        return StatusViewCommand.Move(chat.id, StatusViewTarget.Pinned(sectionId), first?.id)
    }

    /**
     * Section header Move up (-1) / Move down (+1) among user sections; the default section stays
     * first. [order] is the user sections' ids in display order.
     */
    fun section(order: List<String>, sectionId: String, delta: Int): StatusViewCommand.MoveSection? {
        val index = order.indexOf(sectionId)
        val next = index + delta
        if (index < 0 || next < 0 || next >= order.size) return null
        val rest = order.filterNot { it == sectionId }
        return StatusViewCommand.MoveSection(sectionId, rest.getOrNull(next))
    }
}
