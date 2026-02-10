package com.neuralbridge.testapp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class FormActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_form)

        val checkboxTerms = findViewById<CheckBox>(R.id.checkbox_terms)
        val radioGroup = findViewById<RadioGroup>(R.id.radio_group_options)
        val dateInput = findViewById<EditText>(R.id.date_input)
        val submitButton = findViewById<Button>(R.id.button_submit)
        val delayedText = findViewById<TextView>(R.id.delayed_text)

        // Hide delayed text initially
        delayedText.visibility = View.GONE

        // Show delayed text after 2 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            delayedText.visibility = View.VISIBLE
            delayedText.text = "This text appeared after 2 seconds"
        }, 2000)

        submitButton.setOnClickListener {
            val isTermsChecked = checkboxTerms.isChecked
            val selectedRadioId = radioGroup.checkedRadioButtonId
            val date = dateInput.text.toString()

            if (isTermsChecked && selectedRadioId != -1 && date.isNotEmpty()) {
                Toast.makeText(this, "Form submitted successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
