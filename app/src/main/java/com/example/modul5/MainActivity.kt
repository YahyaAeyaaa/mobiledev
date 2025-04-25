package com.example.modul5

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.nio.charset.Charset

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: ImageButton
    private lateinit var welcomeText: TextView
    private lateinit var logoutButton: TextView
    private lateinit var adminSidebar: LinearLayout
    private lateinit var userSidebar: LinearLayout
    private lateinit var adminEmail: TextView

    private val logoutHandler = Handler(Looper.getMainLooper())
    private val logoutRunnable = Runnable {
        AlertDialog.Builder(this)
            .setTitle("Sesi Habis")
            .setMessage("Refresh token telah kedaluwarsa, silakan login ulang.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> performLogout() }
            .show()
    }

    // Interface untuk panggilan API yang butuh access token
    interface ProtectedApi {
        @GET("users")
        fun getAllUsers(): Call<List<LoginActivity.UserData>>
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cek sesi sebelum menampilkan UI
        prefs = getSharedPreferences("userPrefs", MODE_PRIVATE)
        if (isSessionExpired()) {
            performLogout()
            return
        }

        setContentView(R.layout.activity_main)
        drawerLayout = findViewById(R.id.drawerLayout)
        menuButton   = findViewById(R.id.menuButton)
        welcomeText  = findViewById(R.id.welcomeText)
        logoutButton = findViewById(R.id.logoutButton)
        adminSidebar = findViewById(R.id.adminSidebarLayout)
        userSidebar  = findViewById(R.id.userSidebarLayout)
        adminEmail   = findViewById(R.id.adminEmailTextView)

        // Tampilkan user info
        welcomeText.text = "Selamat datang, ${prefs.getString("full_name", "")}!"
        adminEmail.text  = prefs.getString("full_name", "")

        // Setup sidebar sesuai role
        when (prefs.getString("role", "")?.lowercase()) {
            "admin" -> {
                menuButton.visibility = View.VISIBLE
                adminSidebar.visibility = View.VISIBLE
                userSidebar.visibility  = View.GONE
            }
            "user" -> {
                menuButton.visibility = View.VISIBLE
                adminSidebar.visibility = View.GONE
                userSidebar.visibility  = View.VISIBLE
            }
            else -> {
                menuButton.visibility = View.GONE
                adminSidebar.visibility = View.GONE
                userSidebar.visibility  = View.GONE
            }
        }

        menuButton.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.END))
                drawerLayout.closeDrawer(GravityCompat.END)
            else
                drawerLayout.openDrawer(GravityCompat.END)
        }

        logoutButton.setOnClickListener { performLogout() }

        // Pasang interceptor untuk auto-refresh access token
        val client = OkHttpClient.Builder()
            .addInterceptor(TokenInterceptor(prefs))
            .build()

        // Buat instance Retrofit dengan ProtectedApi (bukan Any::class)
        val protectedApi = Retrofit.Builder()
            .baseUrl(LoginActivity.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ProtectedApi::class.java)

        // Contoh pemanggilan API terproteksi
        // protectedApi.getAllUsers().enqueue(...)

        // Schedule logout berdasarkan expiry refresh token
        scheduleLogout()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        logoutHandler.removeCallbacks(logoutRunnable)
        scheduleLogout()
    }

    private fun scheduleLogout() {
        logoutHandler.removeCallbacks(logoutRunnable)
        val expRefresh = prefs.getLong("refresh_exp", 0L)
        val delay      = expRefresh - System.currentTimeMillis()
        if (delay > 0) logoutHandler.postDelayed(logoutRunnable, delay)
        else performLogout()
    }

    private fun isSessionExpired(): Boolean {
        val expRefresh = prefs.getLong("refresh_exp", 0L)
        val token      = prefs.getString("access_token", null)
        return token.isNullOrEmpty() || System.currentTimeMillis() > expRefresh
    }

    private fun performLogout() {
        prefs.edit().clear().apply()
        Toast.makeText(this, "Silakan login kembali.", Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, LoginActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK }
        )
        finish()
    }

    class TokenInterceptor(private val prefs: SharedPreferences) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val token    = prefs.getString("access_token", "") ?: ""
            val request  = original.newBuilder()
                .apply { if (token.isNotEmpty()) addHeader("Authorization", "Bearer $token") }
                .build()

            var response = chain.proceed(request)
            if (response.code() == 401) {
                response.close()
                // Refresh access token secara sinkron
                val oldRt = prefs.getString("refresh_token", "") ?: ""
                val newAt = Retrofit.Builder()
                    .baseUrl(LoginActivity.BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(LoginActivity.ApiService::class.java)
                    .refreshToken(LoginActivity.RefreshRequest(oldRt))
                    .execute()
                    .body()
                    ?.accessToken

                newAt?.let { updatedToken ->
                    // Update expiry access token
                    val part    = updatedToken.split(".")[1]
                    val payload = String(Base64.decode(part, Base64.URL_SAFE), Charset.forName("UTF-8"))
                    val expMs   = JSONObject(payload).getLong("exp") * 1000L
                    prefs.edit()
                        .putString("access_token", updatedToken)
                        .putLong("access_exp", expMs)
                        .apply()

                    // Ulangi request dengan token baru
                    response = chain.proceed(
                        original.newBuilder()
                            .addHeader("Authorization", "Bearer $updatedToken")
                            .build()
                    )
                }
            }
            return response
        }
    }
}
