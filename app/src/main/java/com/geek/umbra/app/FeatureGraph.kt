package com.geek.umbra.app

import com.geek.umbra.feature.chat.ChatModule
import com.geek.umbra.feature.remote.RemoteModule
import com.geek.umbra.feature.reminder.ReminderModule
import com.geek.umbra.feature.tool.ToolModule
import com.geek.umbra.feature.task.TaskModule
import com.geek.umbra.feature.inspiration.InspirationModule
import com.geek.umbra.feature.money.MoneyModule
import com.geek.umbra.feature.me.MeModule
import com.geek.umbra.feature.phrases.PhrasesModule
import com.geek.umbra.feature.account.AccountModule
import com.geek.umbra.feature.memory.MemoryModule
import com.geek.umbra.feature.knowledge.KnowledgeModule
import com.geek.umbra.feature.duty.DutyModule
import com.geek.umbra.feature.vault.VaultModule

/** 各功能模块的守卫登记集中在这一处调用，模块自己的 Store 在各自包里（object 单例，用到才建）。 */
object FeatureGraph {
    fun registerGuards(guard: SessionGuard) {
        ChatModule.register(guard)
        ReminderModule.register(guard)
        ToolModule.register(guard)
        TaskModule.register(guard)
        InspirationModule.register(guard)
        MoneyModule.register(guard)
        MeModule.register(guard)
        PhrasesModule.register(guard)
        AccountModule.register(guard)
        MemoryModule.register(guard)
        KnowledgeModule.register(guard)
        DutyModule.register(guard)
        VaultModule.register(guard)
        RemoteModule.register(guard)
    }
}
