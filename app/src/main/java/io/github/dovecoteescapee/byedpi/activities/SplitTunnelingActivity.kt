package io.github.dovecoteescapee.byedpi.activities

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.dovecoteescapee.byedpi.R
import kotlinx.coroutines.*

data class AppItem(val name: String, val packageName: String, val icon: Drawable, var isBypassed: Boolean)

class SplitTunnelingActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var searchBox: EditText
    private lateinit var adapter: SplitTunnelingAdapter
    private val allApps = mutableListOf<AppItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_split_tunneling)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = "Split Tunneling"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recycler_view)
        progressBar = findViewById(R.id.progress_bar)
        searchBox = findViewById(R.id.search_box)

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadApps()

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@SplitTunnelingActivity)
            val bypassedSet = prefs.getStringSet("bypass_apps", emptySet()) ?: emptySet()

            val appItems = mutableListOf<AppItem>()
            for (appInfo in packages) {
                // Only show apps that can be launched by user to avoid cluttering with system services
                if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    val isBypassed = bypassedSet.contains(appInfo.packageName)
                    appItems.add(AppItem(name, appInfo.packageName, icon, isBypassed))
                }
            }
            appItems.sortBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(appItems)
                adapter = SplitTunnelingAdapter(allApps) { appItem, isChecked ->
                    updateBypassSetting(appItem.packageName, isChecked)
                }
                recyclerView.adapter = adapter
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateBypassSetting(packageName: String, isBypassed: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val bypassedSet = prefs.getStringSet("bypass_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        if (isBypassed) {
            bypassedSet.add(packageName)
        } else {
            bypassedSet.remove(packageName)
        }
        
        prefs.edit().putStringSet("bypass_apps", bypassedSet).apply()
    }
}

class SplitTunnelingAdapter(
    private var apps: List<AppItem>,
    private val onAppToggled: (AppItem, Boolean) -> Unit
) : RecyclerView.Adapter<SplitTunnelingAdapter.ViewHolder>() {

    private var filteredApps: List<AppItem> = apps

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val name: TextView = view.findViewById(R.id.app_name)
        val packageName: TextView = view.findViewById(R.id.app_package)
        val checkBox: CheckBox = view.findViewById(R.id.app_checkbox)

        init {
            view.setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val app = filteredApps[position]
                    app.isBypassed = checkBox.isChecked
                    onAppToggled(app, app.isBypassed)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filteredApps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.name
        holder.packageName.text = app.packageName
        holder.checkBox.isChecked = app.isBypassed
    }

    override fun getItemCount() = filteredApps.size

    fun filter(query: String) {
        val lowerQuery = query.lowercase()
        filteredApps = if (query.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.name.lowercase().contains(lowerQuery) || it.packageName.lowercase().contains(lowerQuery)
            }
        }
        notifyDataSetChanged()
    }
}
