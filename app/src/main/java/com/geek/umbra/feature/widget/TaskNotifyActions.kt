// 任务通知上那几颗动作钮的落点（设计侧第 199 封答复第 9 条）。
//
// 为什么要一个跳板 Activity：MainActivity（app/，不归本包）只认 umbra:// 深链，而「去确认」要进秘书会话
// （没有深链，要调 ChatApi.focus）、「停止」要先记下一次性标记再进详情、「重新发起」要预填输入框。
// 通知的 PendingIntent 直接指向这个不显示界面的 Activity：做完那一步路由动作就把 MainActivity 拉到前台、自己 finish。
// Android 12+ 禁止「广播接收器 / 服务当通知跳板再起 Activity」，所以要进 App 的动作一律走 Activity，不走广播。
//
// 「重试任务」不进 App：走广播接收器在后台直接 POST /tasks/{id}/retry。
// ⛔ 答复说「失败就打开详情」—— 后台广播里起 Activity 正是 Android 12+ 禁掉的通知跳板，做不到；
// 改成把那条失败通知原地换成重试没成的那句话（TaskRetryOutcome.message），点通知本体照样进详情。
package com.geek.umbra.feature.widget

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.geek.umbra.app.AppGraph
import com.geek.umbra.app.MainActivity
import com.geek.umbra.feature.chat.ChatApi
import com.geek.umbra.feature.task.TaskRetryOutcome
import com.geek.umbra.feature.task.TaskStopRequest
import com.geek.umbra.feature.task.TasksStore
import com.geek.umbra.navigation.DeepLinks
import com.geek.umbra.navigation.Route
import kotlinx.coroutines.launch

/** 动作名与 extra 键。 */
internal object TaskNotifyIntents {
    const val EXTRA_ACTION = "umbra.task.notify.action"
    const val EXTRA_TASK = "umbra.task.notify.task"
    const val EXTRA_TEXT = "umbra.task.notify.text"

    const val STOP = "stop"
    const val CONFIRM = "confirm"
    const val RELAUNCH = "relaunch"
    const val CAPS = "caps"
    const val RETRY = "com.geek.umbra.task.RETRY"

    /** 跳板 Activity 的 PendingIntent。requestCode 按「任务 + 动作」区分，免得同一任务的几颗钮互相覆盖 extra。 */
    fun activity(ctx: Context, action: String, taskId: String, text: String? = null): PendingIntent =
        PendingIntent.getActivity(
            ctx, (taskId + action).hashCode(),
            Intent(ctx, TaskNotifyActivity::class.java)
                .putExtra(EXTRA_ACTION, action).putExtra(EXTRA_TASK, taskId).putExtra(EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 「重试任务」：广播，后台发。 */
    fun retry(ctx: Context, taskId: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, (taskId + RETRY).hashCode(),
            Intent(ctx, TaskNotifyReceiver::class.java).setAction(RETRY).putExtra(EXTRA_TASK, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/**
 * 不显示界面的跳板（manifest 里 Theme.NoDisplay、不进最近任务）。在 onCreate 里做完就 finish ——
 * NoDisplay 主题要求在 onResume 之前 finish。
 * 未登录时联网的那几条（停止 / 去确认 / 重新发起 / 去看能力）只把 App 打开、不跳：同 DeepLinks 的「门关时要登录的页丢弃」。
 */
class TaskNotifyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(TaskNotifyIntents.EXTRA_TASK).orEmpty()
        val signedIn = !AppGraph.guard.locked.value
        when (intent?.getStringExtra(TaskNotifyIntents.EXTRA_ACTION)) {
            TaskNotifyIntents.STOP -> if (id.isNotEmpty()) {
                // 先记标记再推详情：详情页拿到详情后读标记弹现成的停止确认（TaskViews.kt confirmStop）。
                TaskStopRequest.request(id)
                DeepLinks.receive("umbra://task/$id")
            }
            // 秘书会话（assistant）：需确认的那句问话就在里面，确认钮挂在任务卡上。
            TaskNotifyIntents.CONFIRM -> if (signedIn) ChatApi.focus("assistant", null)
            TaskNotifyIntents.RELAUNCH -> if (signedIn) intent?.getStringExtra(TaskNotifyIntents.EXTRA_TEXT)?.let { ChatApi.startDraft(it) }
            TaskNotifyIntents.CAPS -> if (signedIn) AppGraph.router.jump(Route.MeCaps)
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        finish()
    }
}

/** 「重试任务」的后台接收器。成了：撤掉失败那条（任务回到执行中，常驻那条会接着出来）；没成：失败那条换成没成的那句话。 */
class TaskNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TaskNotifyIntents.RETRY) return
        val id = intent.getStringExtra(TaskNotifyIntents.EXTRA_TASK)?.takeIf { it.isNotEmpty() } ?: return
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                val o = TasksStore.runRetry(id)
                if (o == TaskRetryOutcome.Ok) TaskProgressNotifier.clearResult(context, id)
                else TaskProgressNotifier.retryFailed(context, id, o.message ?: "")
                // 收尾（重拉列表、成功后 2 秒再拉）照详情页那颗钮的做法；重拉会经 WidgetBridge 驱动常驻通知。
                if (o == TaskRetryOutcome.Missing) TasksStore.markGone(id) else if (o.refreshes) TasksStore.loadTasks()
            } finally {
                pending.finish()
            }
        }
    }
}
