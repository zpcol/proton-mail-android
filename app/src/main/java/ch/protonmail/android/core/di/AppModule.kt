/*
 * Copyright (c) 2020 Proton Technologies AG
 * 
 * This file is part of ProtonMail.
 * 
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.core.di

import android.content.Context
import android.content.SharedPreferences
import ch.protonmail.android.activities.messageDetails.MessageRenderer
import ch.protonmail.android.adapters.swipe.SwipeProcessor
import ch.protonmail.android.api.DnsOverHttpsProviderRFC8484
import ch.protonmail.android.api.NetworkConfigurator
import ch.protonmail.android.api.NetworkSwitcher
import ch.protonmail.android.api.OkHttpProvider
import ch.protonmail.android.api.ProtonMailApi
import ch.protonmail.android.api.ProtonMailApiManager
import ch.protonmail.android.api.ProtonMailApiProvider
import ch.protonmail.android.api.ProtonRetrofitBuilder
import ch.protonmail.android.api.models.DatabaseProvider
import ch.protonmail.android.api.models.doh.Proxies
import ch.protonmail.android.api.models.room.attachmentMetadata.AttachmentMetadataDatabase
import ch.protonmail.android.api.models.room.attachmentMetadata.AttachmentMetadataDatabaseFactory
import ch.protonmail.android.api.models.room.messages.MessagesDatabase
import ch.protonmail.android.api.models.room.messages.MessagesDatabaseFactory
import ch.protonmail.android.api.models.room.pendingActions.PendingActionsDatabase
import ch.protonmail.android.api.models.room.pendingActions.PendingActionsDatabaseFactory
import ch.protonmail.android.api.segments.contact.ContactEmailsManager
import ch.protonmail.android.api.segments.event.EventManager
import ch.protonmail.android.bl.HtmlProcessor
import ch.protonmail.android.core.BigContentHolder
import ch.protonmail.android.core.Constants
import ch.protonmail.android.core.NetworkResults
import ch.protonmail.android.core.PREF_USERNAME
import ch.protonmail.android.core.ProtonMailApplication
import ch.protonmail.android.core.QueueNetworkUtil
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.domain.usecase.DownloadFile
import ch.protonmail.android.domain.util.DispatcherProvider
import ch.protonmail.android.jobs.ProtonMailBaseJob
import ch.protonmail.android.utils.AppUtil
import ch.protonmail.android.utils.Logger
import ch.protonmail.android.utils.crypto.OpenPGP
import ch.protonmail.libs.core.preferences.get
import com.birbit.android.jobqueue.JobManager
import com.birbit.android.jobqueue.config.Configuration
import com.birbit.android.jobqueue.log.CustomLogger
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Main Dagger Module for the App
 *
 * TODO: remove useless fields* and re-organise with a proper pattern
 *  * Every constructor annotated with @[Inject] doesn't need to be declared manually here
 */
@Module
class AppModule(val app: ProtonMailApplication) {

    private val alternativeApiPins = listOf(
        "EU6TS9MO0L/GsDHvVc9D5fChYLNy5JdGYpJw0ccgetM=",
        "iKPIHPnDNqdkvOnTClQ8zQAIKG0XavaPkcEo0LBAABA=",
        "MSlVrBCdL0hKyczvgYVSRNm88RicyY04Q2y5qrBt0xA=",
        "C2UxW0T1Ckl9s+8cXfjXxlEqwAfPM4HiW2y3UdtBeCw="
    )

    private val dnsOverHttpsProviders = arrayOf(
        DnsOverHttpsProviderRFC8484("https://dns11.quad9.net/dns-query/"),
        DnsOverHttpsProviderRFC8484("https://dns.google/dns-query/")
    )

    @Provides
    @Singleton
    fun provideApplication(): ProtonMailApplication = app

    @Provides
    @Singleton
    fun provideApplicationContext(): Context = app.applicationContext

    @Provides
    @Singleton
    fun provideNetworkUtil(
        networkConfigurator: NetworkConfigurator
    ): QueueNetworkUtil = QueueNetworkUtil(app, networkConfigurator)

    @Provides
    @Singleton
    fun provideNetworkConfigurator(
        @Named(Constants.PrefsType.DEFAULT) prefs: SharedPreferences
    ): NetworkConfigurator = NetworkConfigurator(dnsOverHttpsProviders, prefs)

    @Provides
    @Singleton
    fun provideNetworkSwitcher(
        apiManager: ProtonMailApiManager,
        apiProvider: ProtonMailApiProvider,
        okHttpProvider: OkHttpProvider,
        @Named(Constants.PrefsType.DEFAULT) prefs: SharedPreferences,
        networkConfigurator: NetworkConfigurator
    ): NetworkSwitcher {
        val switcher = NetworkSwitcher(apiManager, apiProvider, okHttpProvider, prefs)
        networkConfigurator.apply {
            networkSwitcher = switcher
        }
        return switcher
    }

    @Provides
    @Singleton
    fun provideProxies(@Named(Constants.PrefsType.DEFAULT) prefs: SharedPreferences): Proxies {
        return Proxies.getInstance(prefs = prefs)
    }

    @Provides
    @Singleton
    fun provideHtmlProcessor(): HtmlProcessor = HtmlProcessor()

    @Provides
    fun provideSwipeProcessor(): SwipeProcessor = SwipeProcessor()

    @Provides
    @Singleton
    fun provideOpenPGP(): OpenPGP = OpenPGP()

    @Provides
    @Singleton
    fun provideEventManager(): EventManager = EventManager()

    @Provides
    @Singleton
    fun provideContactEmailsManager(
        protonMailApi: ProtonMailApiManager,
        databaseProvider: DatabaseProvider
    ): ContactEmailsManager = ContactEmailsManager(protonMailApi, databaseProvider)

    @Provides
    @Singleton
    fun provideDatabaseProvider(context: Context): DatabaseProvider = DatabaseProvider(context)

    @Provides
    @Singleton
    @Named("messages")
    fun provideMessagesDatabase(
        @Named("messages_factory") messagesDatabaseFactory: MessagesDatabaseFactory
    ): MessagesDatabase = messagesDatabaseFactory.getDatabase()

    @Provides
    @Singleton
    @Named("messages_factory")
    fun provideMessagesDatabaseFactory(app: ProtonMailApplication): MessagesDatabaseFactory =
        MessagesDatabaseFactory.getInstance(app)

    @Provides
    fun provideMessageRendererFactory(app: ProtonMailApplication) = MessageRenderer.Factory(
        File(app.filesDir, Constants.DIR_EMB_ATTACHMENT_DOWNLOADS)
    )

    @Provides
    @Singleton
    @Named("messages_search")
    fun provideSearchMessagesDatabase(
        @Named("messages_search_factory") messagesDatabaseFactory: MessagesDatabaseFactory
    ): MessagesDatabase = messagesDatabaseFactory.getDatabase()

    @Provides
    @Singleton
    @Named("messages_search_factory")
    fun provideSearchMessagesDatabaseFactory(app: ProtonMailApplication): MessagesDatabaseFactory =
        MessagesDatabaseFactory.getSearchDatabase(app)

    @Provides
    @Singleton
    fun providePendingActionsDatabase(
        pendingActionsDatabaseFactory: PendingActionsDatabaseFactory
    ): PendingActionsDatabase = pendingActionsDatabaseFactory.getDatabase()

    @Provides
    @Singleton
    fun providePendingActionsDatabaseFactory(app: ProtonMailApplication): PendingActionsDatabaseFactory =
        PendingActionsDatabaseFactory.getInstance(app)

    @Provides
    @Singleton
    fun provideAttachmentMetadataDatabase(app: ProtonMailApplication): AttachmentMetadataDatabase =
        AttachmentMetadataDatabaseFactory.getInstance(app).getDatabase()

    @Provides
    @Singleton
    fun provideProtonRetrofitBuilder(
        userManager: UserManager,
        jobManager: JobManager,
        networkUtil: QueueNetworkUtil,
        okHttpProvider: OkHttpProvider,
        @Named(Constants.PrefsType.DEFAULT) prefs: SharedPreferences
    ): ProtonRetrofitBuilder {

        // userManager.user.allowSecureConnectionsViaThirdParties)
        val dnsOverHttpsHost = if (!userManager.user.usingDefaultApi)
            Proxies.getInstance(null, prefs).getCurrentWorkingProxyDomain() else Constants.ENDPOINT_URI

        // val dnsOverHttpsHost = Proxies.getInstance(null, prefs).getCurrentWorkingProxyDomain()

        val builder = ProtonRetrofitBuilder(userManager, jobManager, networkUtil, okHttpProvider, dnsOverHttpsHost)
        builder.rebuildMapFor(okHttpProvider, dnsOverHttpsHost)
        return builder
    }

    // region retrofit
    @Provides
    @Singleton
    fun provideOkHttpClientProvider() = OkHttpProvider(alternativeApiPins)

    // endregion

    @Provides
    @Singleton
    fun provideProtonMailApiProvider(protonRetrofitBuilder: ProtonRetrofitBuilder): ProtonMailApiProvider =
        ProtonMailApiProvider(protonRetrofitBuilder)

    @Inject
    @Provides
    @Singleton
    fun provideApiManager(protonMailApi: ProtonMailApi) = ProtonMailApiManager(protonMailApi)

    @Provides
    @Singleton
    fun provideUserManager(
        @Named(Constants.PrefsType.DEFAULT) prefs: SharedPreferences,
        @Named(Constants.PrefsType.BACKUP) backupPrefs: SharedPreferences,
        app: ProtonMailApplication
    ) = UserManager(prefs, backupPrefs, app.applicationContext)

    @Provides
    @Singleton
    fun provideNetworkResults(): NetworkResults = NetworkResults()

    @Provides
    @Singleton
    fun provideBigContentHolder(): BigContentHolder = BigContentHolder()

    @Provides
    @Singleton
    @Named(Constants.PrefsType.DEFAULT)
    fun provideDefaultSharedPreferences(): SharedPreferences = app.defaultSharedPreferences

    @Provides
    @Singleton
    @Named(Constants.PrefsType.BACKUP)
    fun provideBackupSharedPreferences(): SharedPreferences =
        app.getSharedPreferences(Constants.PrefsType.BACKUP_PREFS_NAME, Context.MODE_PRIVATE)

    @Provides
    @Singleton
    fun provideJobManager(queueNetworkUtil: QueueNetworkUtil): JobManager {
        val config = Configuration.Builder(app)
                .minConsumerCount(1)
                .consumerKeepAlive(120) // 2 minutes
                .networkUtil(queueNetworkUtil)
                .injector { job ->
                    if (job is ProtonMailBaseJob) {
                        app.appComponent.inject(job)
                    }
                }
                .customLogger(object : CustomLogger {
                    override fun v(text: String, vararg args: Any?) {
                        if (isDebugEnabled) {
                            Logger.doLog(TAG, String.format(text, *args))
                        }
                    }

                    private val TAG = "JOBS"

                    override fun isDebugEnabled(): Boolean {
                        return false
                    }

                    override fun d(text: String, vararg args: Any) {
                        if (isDebugEnabled) {
                            Logger.doLog(TAG, String.format(text, *args))
                        }
                    }

                    override fun e(t: Throwable, text: String, vararg args: Any) {
                        Logger.doLogException(TAG, String.format(text, *args), t)
                    }

                    override fun e(text: String, vararg args: Any) {
                        Logger.doLogException(TAG, String.format(text, *args), null)
                    }
                })
                .build()

        return JobManager(config)
    }


    /* * * * * SORTED ALPHABETICALLY BELOW!! * * * * */

    @Provides
    @Named(CURRENT_USERNAME)
    fun provideCurrentUserUsername(
        @Named(Constants.PrefsType.DEFAULT) prefs: SharedPreferences
    ): String = prefs[PREF_USERNAME]!!

    @Provides
    fun provideDispatcherProvider() = object : DispatcherProvider {
        override val Io = Dispatchers.IO
        override val Comp = Dispatchers.Default
        override val Main = Dispatchers.Main
    }

    // TODO move to data module into a proper class
    @Provides
    @Suppress("BlockingMethodInNonBlockingContext") // Network call launched in a Coroutines, which are not
    //                                                          recognised as non blocking scope
    fun provideDownloadFile(dispatcherProvider: DispatcherProvider) = object : DownloadFile {
        override suspend fun invoke(url: String): InputStream {
            return withContext(dispatcherProvider.Io) {
                // hardcoded timeout - this network call should be implemented in the data layer anyway
                withTimeout(10_000) {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.inputStream
                }
            }
        }
    }

    @Provides
    fun provideMailSettings(
        userManager: UserManager,
        @Named(CURRENT_USERNAME) username: String
    ) = userManager.getMailSettings(username)

    /* * * * * SORTED ALPHABETICALLY ABOVE!! * * * * */
}

// Dependency names
// region User
private const val CURRENT_USERNAME = "username"
// endregion
