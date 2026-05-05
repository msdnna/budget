package website.msdnna.budget_app

import android.app.Application
import website.msdnna.budget_app.data.AppContainer

class BudgetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}
