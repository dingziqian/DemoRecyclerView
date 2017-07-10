package com.ding.demorecyclerview;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ding.widget.RecyclerView;

/**
 * Created by zzandroid on 2017/7/10.
 */

public class DataAdapter extends RecyclerView.Adapter<DataAdapter.TextViewHolder> {

    List<String> datas;

    public DataAdapter() {
        datas = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            datas.add(i + "");
        }

    }

    @Override
    public TextViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new TextViewHolder(getTextView(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(TextViewHolder holder, int position) {
        TextView text = (TextView) holder.itemView;
        text.setText(datas.get(position));
    }

    @Override
    public int getItemCount() {
        return datas.size();
    }

    class TextViewHolder extends RecyclerView.ViewHolder {
        public TextViewHolder(View itemView) {
            super(itemView);
        }
    }


    private TextView getTextView(Context context) {
        TextView textView = new TextView(context);
        textView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        textView.setGravity(Gravity.CENTER);
        textView.setTextColor(Color.RED);
        textView.setTextSize(30);
        return textView;
    }


}
