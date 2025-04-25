package com.example.modul5

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.nio.charset.Charset

class LoginActivity : AppCompatActivity() {
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var prefs: SharedPreferences
    private lateinit var api: ApiService

    companion object {
        // Sesuaikan IP/port-mu.
        // Di emulator gunakan 10.0.2.2, di perangkat nyata ganti dengan IP PC-mu.
        const val BASE_URL = "http://192.168.111.190:5000/api/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        prefs             = getSharedPreferences("userPrefs", MODE_PRIVATE)
        emailEditText     = findViewById(R.id.emailEditText)
        passwordEditText  = findViewById(R.id.passwordEditText)
        loginButton       = findViewById(R.id.loginButton)

        api = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)

        loginButton.setOnClickListener { loginUser() }
    }

    private fun loginUser() {
        val email = emailEditText.text.toString().trim()
        val pass  = passwordEditText.text.toString().trim()
        if (email.isBlank() || pass.isBlank()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        api.loginUser(LoginRequest(email, pass)).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!

                    // Simpan tokens & decode expiry
                    val at = body.accessToken
                    val rt = body.refreshToken
                    fun decodeExp(token: String): Long {
                        val part = token.split(".")[1]
                        val json = String(Base64.decode(part, Base64.URL_SAFE), Charset.forName("UTF-8"))
                        return JSONObject(json).getLong("exp") * 1000L
                    }

                    prefs.edit().apply {
                        putString("full_name",    body.data.fullName)
                        putString("role",         body.data.role)
                        putString("access_token", at)
                        putString("refresh_token", rt)
                        putLong("access_exp",     decodeExp(at))
                        putLong("refresh_exp",    decodeExp(rt))
                        apply()
                    }

                    Toast.makeText(this@LoginActivity, body.message, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(
                        this@LoginActivity,
                        "Login gagal: ${response.message()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                Toast.makeText(this@LoginActivity, "Network error: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    interface ApiService {
        @POST("login")
        fun loginUser(@Body req: LoginRequest): Call<LoginResponse>

        @POST("token-refresh")
        fun refreshToken(@Body req: RefreshRequest): Call<RefreshResponse>
    }

    data class LoginRequest(val email: String, val password: String)
    data class LoginResponse(
        val status: Int,
        val message: String,
        val data: UserData,
        @SerializedName("accessToken")  val accessToken: String,
        @SerializedName("refreshToken") val refreshToken: String
    )
    data class UserData(
        @SerializedName("full_name") val fullName: String,
        val email: String,
        val role: String
    )
    data class RefreshRequest(val refreshToken: String)
    data class RefreshResponse(
        val message: String,
        @SerializedName("accessToken") val accessToken: String
    )
}