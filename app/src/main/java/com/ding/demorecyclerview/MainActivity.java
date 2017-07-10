package com.ding.demorecyclerview;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import ding.widget.LinearLayoutManager;
import ding.widget.RecyclerView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        DataAdapter adapter = new DataAdapter();
        RecyclerView re = (RecyclerView) findViewById(R.id.rv);
        re.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        re.setAdapter(adapter);

    }
}
