package com.Chips.yolo11ncnn;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.Chips.yolo11ncnn.databinding.MainBinding;


public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        MainBinding binding = MainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.YOLOButton.setOnClickListener(v->{
            Intent intent = new Intent(this, YOLO.class);
            startActivity(intent);
        });

        binding.CameraButton.setOnClickListener(v->{
            Intent intent = new Intent(this,V.class);
            startActivity(intent);
        });

    }
}
