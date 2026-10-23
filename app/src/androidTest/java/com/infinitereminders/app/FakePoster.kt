package com.infinitereminders.app

import android.app.Notification
import com.infinitereminders.app.notify.ActiveNotif
import com.infinitereminders.app.notify.Notif
import com.infinitereminders.app.notify.NotificationPoster

class FakePoster : NotificationPoster {
    var enabled = true
    var failNotify = false
    val posts = LinkedHashMap<Pair<String, Int>, Notification>()
    var notifyCalls = 0

    override fun areEnabled() = enabled
    override fun notify(tag: String, id: Int, n: Notification) {
        notifyCalls++
        if (failNotify) throw IllegalStateException("simulated registration failure")
        posts[tag to id] = n
    }
    override fun cancel(tag: String, id: Int) { posts.remove(tag to id) }
    override fun active() = posts.map { (k, v) -> ActiveNotif(k.first, k.second, v.extras.getString(Notif.EXTRA_SIG)) }

    fun reminderIds() = posts.keys.filter { it.first == Notif.TAG_R }.map { it.second }
    fun actions(id: Int) = posts[Notif.TAG_R to id]?.actions?.map { it.title.toString() } ?: emptyList()
    fun swipeAway(id: Int) { posts.remove(Notif.TAG_R to id) }
}
