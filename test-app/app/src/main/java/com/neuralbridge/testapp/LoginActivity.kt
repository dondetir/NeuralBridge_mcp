package com.neuralbridge.testapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val usernameEdit = findViewById<EditText>(R.id.username)
        val passwordEdit = findViewById<EditText>(R.id.password)
        val loginButton = findViewById<Button>(R.id.button_login)

        loginButton.setOnClickListener {
            val username = usernameEdit.text.toString()
            val password = passwordEdit.text.toString()

            if (username.isNotEmpty() && password.isNotEmpty()) {
                Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, ListActivity::class.java))
            } else {
                Toast.makeText(this, "Please enter credentials", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
