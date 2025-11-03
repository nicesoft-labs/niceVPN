package ru.nicesoft.openvpn.data

import android.content.Context
import de.blinkt.openvpn.core.Preferences
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.VpnProfile

class ActiveProfileStore(private val context: Context) {

    fun getActiveProfile(): VpnProfile? {
        val uuid = Preferences.getDefaultSharedPreferences(context)
            .getString(PREF_IMPORTED_PROFILE_UUID, null)
            ?: return null

        return ProfileManager.get(context, uuid)
    }

    fun setActiveProfile(profile: VpnProfile) {
        Preferences.getDefaultSharedPreferences(context)
            .edit()
            .putString(PREF_IMPORTED_PROFILE_UUID, profile.uuidString)
            .apply()
    }

    fun clear() {
        Preferences.getDefaultSharedPreferences(context)
            .edit()
            .remove(PREF_IMPORTED_PROFILE_UUID)
            .apply()
    }

    companion object {
        private const val PREF_IMPORTED_PROFILE_UUID = "simple_imported_profile_uuid"
    }
}
