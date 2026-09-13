package com.blackcode.cascoscan

import android.app.Application
import com.blackcode.cascoscan.data.AuditRepository
import com.blackcode.cascoscan.data.CascoDatabase
import com.blackcode.cascoscan.detect.DetectionConfig
import com.blackcode.cascoscan.domain.DetectionService
import com.blackcode.cascoscan.domain.ReconcileService

/**
 * Hand-rolled dependency container.
 *
 * The graph is five objects deep and every one of them is a singleton for the life of the process; a
 * DI framework here would add build time and indirection without removing a single line of wiring.
 */
class CascoScanApp : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }

    class Container(application: Application) {
        /** Application context, for the screens that have to rasterise a page themselves. */
        val context: android.content.Context = application
        val detectionConfig: DetectionConfig = DetectionConfig()
        val siteConfig: com.blackcode.cascoscan.site.SiteConfig = com.blackcode.cascoscan.site.SiteConfig()
        private val database = CascoDatabase.create(application)
        val repository = AuditRepository(application, database)
        val detection = DetectionService(application, repository)
        val reconcile = ReconcileService(repository)
    }
}
