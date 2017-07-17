/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ding.widget;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责管理和访问 RecyclerView 的子视图
 * Helper class to manage children.
 * 这个类包装了RecyclerView并且能够隐藏一些子View，这里会提供俩个set方法，这里是复制了ViewGroup里面的一些常规方法
 * 比如 getChildAt getChildCount 等，这些方法会忽略被hide的子View。
 * 当RecyclerView需要直接访问他的子类的时候会，他可以调用 getUnfilteredChildCount getUnfilteredChildAt 等方法
 * <p>
 * It wraps a RecyclerView and adds ability to hide some children. There are two sets of methods
 * provided by this class. <b>Regular</b> methods are the ones that replicate ViewGroup methods
 * like getChildAt, getChildCount etc. These methods ignore hidden children.
 * <p>
 * When RecyclerView needs direct access to the view group children, it can call unfiltered
 * methods like get getUnfilteredChildCount or getUnfilteredChildAt.
 *
 * RecyclerView布局的时候还，需要考虑一种情况：当进行动画的时候，ViewGroup上的View不是立马就被remove的(动画结束后才会被真正的移除)，
 * 而data里面的数据是里面就被移除的，这就造成了ViewGroup和data的数据是不一致的，在RecyclerView中，被称作为AnimatingView，
 * 显然这种不一致是会对RecyclerView造成影响，在真正布局的时候，RecyclerView的LayoutManager在进行布局时是不会也不能考虑上面的AnimatingView的
 * 但是某些场景下，RecyclerView又需要能够感知到这些AnimatingView(比如View复用)，这个时候就会造成下面的情况：
 *     ViewGroup认为所有的childView是可以见的
 *     Data认为，只有能够映射到Data的ChildView才是可见的
 * 为了支持上面的两种情况，就需要进行额外的处理
 * recyclerView将ChildView管理职责全权委托给了ChildHelper，所有关于ChildView的操作都要通过ChildHelper来间接进行,
 * ChildHelper成为了一个ChildView操作的中间层，getChildCount/getChildAt等函数经由ChildHelper的拦截处理再下发给RecyclerView的对应函数，
 * 其参数或者返回结果会根据实际的ChildView信息进行改写(可以发现，各个方法对index的处理，都是会先经过getOffset这个方法，它拦截了真正的index，
 * 返回的是根据mBucket映射的各个状态的View的index)，所以，ChildHelper只是一个中间处理器，真正涉及到具体的ChildView操作还需要落地到RecyclerView
 *
 * ChildHelper包含了两套ChildView序列，一套序列只包含了可见的ChildView(mCallback中的序列)，另一套则包含了所有ChildView(mBucket所维护)
 * 举个例子：RecyclerView中有 A,B,C,D,E,F 6个ChildView，其中 B，C，E 是不可见的, 这里就存在两个ChildView序列:
 *     [1] 所有ChildView: A, B, C, D, E, F
 *     [2] 可见ChildView: A, D, F.
 *
 * 对[1]getChildCount是6, 对[2]getChildCount是3.
 * 对[1]getChildAt(1)得到的是B, 对[2]getChildAt(1)得到的是D
 *
 * 再看一下这里面的addView的方法，该方法只针对可见ChildView, 如果指定了要add的index(在序列中的位置),
 * 那么会根据真实ChildView的情况，对index进行偏移(getOffset函数非常关键，会基于Bucket算出合适的偏移结果)，算出一个在真实ChildView序列中的index，
 * 将这个新的index作为View在ViewGroup中的index进行添加，在将View按照新的index进行添加后，Bucket中的映射关系也会进行相应的insert
 * 举个例子：在[2]的index 2位置插入G(addView(G, 2)), 那么两套序列会变为:
 *     [1] A,B,C,D,G,E,F
 *     [2] A,D,G,F
 * addView传输的index是2, 经过ChildHelper转化，最后偏移为4插入到了RecyclerView中。
 *
 * http://blog.csdn.net/fyfcauc/article/details/54175072
 *
 */
class ChildHelper {

    private static final boolean DEBUG = false;

    private static final String TAG = "ChildrenHelper";

    final Callback mCallback;

    // 可以理解为一个List<Boolean>,这个变量是维护的一个映射，ChildView的位置是key，value则是否是特殊的View
    // 将普通ChildView看作是可见的，特殊的View看作是不可见的
    final Bucket mBucket;

    final List<View> mHiddenViews;

    ChildHelper(Callback callback) {
        mCallback = callback;
        mBucket = new Bucket();
        mHiddenViews = new ArrayList<View>();
    }

    /**
     * 标记View为Hidden,这里是把对应的View放入到 mHiddenViews
     * Marks a child view as hidden
     *
     * @param child  View to hide.
     */
    private void hideViewInternal(View child) {
        mHiddenViews.add(child);
        mCallback.onEnteredHiddenState(child);
    }

    /**
     * 去除hidden的标记，从mHiddenViews移除
     * Unmarks a child view as hidden.
     *
     * @param child  View to hide.
     */
    private boolean unhideViewInternal(View child) {
        if (mHiddenViews.remove(child)) {
            mCallback.onLeftHiddenState(child);
            return true;
        } else {
            return false;
        }
    }

    /**
     * addView
     * Adds a view to the ViewGroup
     *
     * @param child  View to add.
     * @param hidden If set to true, this item will be invisible from regular methods.
     */
    void addView(View child, boolean hidden) {
        addView(child, -1, hidden);
    }

    /**
     * 在指定的index添加View,这里先要处理对应的mBucket，让后让真正addView的操作是在mCallBack中进行的
     * 也就是RecyclerView当中进行真正的addView，这里处理部分逻辑
     * Add a view to the ViewGroup at an index
     *
     * @param child  View to add.
     * @param index  Index of the child from the regular perspective (excluding hidden views).
     *               ChildHelper offsets this index to actual ViewGroup index.
     * @param hidden If set to true, this item will be invisible from regular methods.
     */
    void addView(View child, int index, boolean hidden) {
        final int offset;
        if (index < 0) {
            offset = mCallback.getChildCount();
        } else {
            offset = getOffset(index);
        }
        mBucket.insert(offset, hidden);
        if (hidden) {
            hideViewInternal(child);
        }
        mCallback.addView(child, offset);
        if (DEBUG) {
            Log.d(TAG, "addViewAt " + index + ",h:" + hidden + ", " + this);
        }
    }

    private int getOffset(int index) {
        if (index < 0) {
            return -1; //anything below 0 won't work as diff will be undefined.
        }
        final int limit = mCallback.getChildCount();
        int offset = index;
        while (offset < limit) {
            final int removedBefore = mBucket.countOnesBefore(offset);
            final int diff = index - (offset - removedBefore);
            if (diff == 0) {
                while (mBucket.get(offset)) { // ensure this offset is not hidden
                    offset ++;
                }
                return offset;
            } else {
                offset += diff;
            }
        }
        return -1;
    }

    /**
     * 移除View，移除mBucket然后移除RecyclerView中的View
     * Removes the provided View from underlying RecyclerView.
     *
     * @param view The view to remove.
     */
    void removeView(View view) {
        int index = mCallback.indexOfChild(view);
        if (index < 0) {
            return;
        }
        if (mBucket.remove(index)) {
            unhideViewInternal(view);
        }
        mCallback.removeViewAt(index);
        if (DEBUG) {
            Log.d(TAG, "remove View off:" + index + "," + this);
        }
    }

    /**
     * 移除指定位置的View
     * Removes the view at the provided index from RecyclerView.
     *
     * @param index Index of the child from the regular perspective (excluding hidden views).
     *              ChildHelper offsets this index to actual ViewGroup index.
     */
    void removeViewAt(int index) {
        final int offset = getOffset(index);
        final View view = mCallback.getChildAt(offset);
        if (view == null) {
            return;
        }
        if (mBucket.remove(offset)) {
            unhideViewInternal(view);
        }
        mCallback.removeViewAt(offset);
        if (DEBUG) {
            Log.d(TAG, "removeViewAt " + index + ", off:" + offset + ", " + this);
        }
    }

    /**
     * Returns the child at provided index.
     *
     * @param index Index of the child to return in regular perspective.
     */
    View getChildAt(int index) {
        final int offset = getOffset(index);
        return mCallback.getChildAt(offset);
    }

    /**
     * Removes all views from the ViewGroup including the hidden ones.
     */
    void removeAllViewsUnfiltered() {
        mBucket.reset();
        for (int i = mHiddenViews.size() - 1; i >= 0; i--) {
            mCallback.onLeftHiddenState(mHiddenViews.get(i));
            mHiddenViews.remove(i);
        }
        mCallback.removeAllViews();
        if (DEBUG) {
            Log.d(TAG, "removeAllViewsUnfiltered");
        }
    }

    /**
     * This can be used to find a disappearing view by position.
     *
     * @param position The adapter position of the item.
     * @param type     View type, can be {@link RecyclerView#INVALID_TYPE}.
     * @return         A hidden view with a valid ViewHolder that matches the position and type.
     */
    View findHiddenNonRemovedView(int position, int type) {
        final int count = mHiddenViews.size();
        for (int i = 0; i < count; i++) {
            final View view = mHiddenViews.get(i);
            RecyclerView.ViewHolder holder = mCallback.getChildViewHolder(view);
            if (holder.getLayoutPosition() == position && !holder.isInvalid() && !holder.isRemoved()
                    && (type == RecyclerView.INVALID_TYPE || holder.getItemViewType() == type)) {
                return view;
            }
        }
        return null;
    }

    /**
     * Attaches the provided view to the underlying ViewGroup.
     *
     * @param child        Child to attach.
     * @param index        Index of the child to attach in regular perspective.
     * @param layoutParams LayoutParams for the child.
     * @param hidden       If set to true, this item will be invisible to the regular methods.
     */
    void attachViewToParent(View child, int index, ViewGroup.LayoutParams layoutParams,
            boolean hidden) {
        final int offset;
        if (index < 0) {
            offset = mCallback.getChildCount();
        } else {
            offset = getOffset(index);
        }
        mBucket.insert(offset, hidden);
        if (hidden) {
            hideViewInternal(child);
        }
        mCallback.attachViewToParent(child, offset, layoutParams);
        if (DEBUG) {
            Log.d(TAG, "attach view to parent index:" + index + ",off:" + offset + "," +
                    "h:" + hidden + ", " + this);
        }
    }

    /**
     * 获取可见的View的数量
     * Returns the number of children that are not hidden.
     *
     * @return Number of children that are not hidden.
     * @see #getChildAt(int)
     */
    int getChildCount() {
        return mCallback.getChildCount() - mHiddenViews.size();
    }

    /**
     * 获取所有的child的数量
     * Returns the total number of children.
     *
     * @return The total number of children including the hidden views.
     * @see #getUnfilteredChildAt(int)
     */
    int getUnfilteredChildCount() {
        return mCallback.getChildCount();
    }

    /**
     * 获取真正的index，childHelper是不会处理这里index的
     * Returns a child by ViewGroup offset. ChildHelper won't offset this index.
     *
     * @param index ViewGroup index of the child to return.
     * @return The view in the provided index.
     */
    View getUnfilteredChildAt(int index) {
        return mCallback.getChildAt(index);
    }

    /**
     * Detaches the view at the provided index.
     *
     * @param index Index of the child to return in regular perspective.
     */
    void detachViewFromParent(int index) {
        final int offset = getOffset(index);
        mBucket.remove(offset);
        mCallback.detachViewFromParent(offset);
        if (DEBUG) {
            Log.d(TAG, "detach view from parent " + index + ", off:" + offset);
        }
    }

    /**
     * Returns the index of the child in regular perspective.
     *
     * @param child The child whose index will be returned.
     * @return The regular perspective index of the child or -1 if it does not exists.
     */
    int indexOfChild(View child) {
        final int index = mCallback.indexOfChild(child);
        if (index == -1) {
            return -1;
        }
        if (mBucket.get(index)) {
            if (DEBUG) {
                throw new IllegalArgumentException("cannot get index of a hidden child");
            } else {
                return -1;
            }
        }
        // reverse the index
        return index - mBucket.countOnesBefore(index);
    }

    /**
     * Returns whether a View is visible to LayoutManager or not.
     *
     * @param view The child view to check. Should be a child of the Callback.
     * @return True if the View is not visible to LayoutManager
     */
    boolean isHidden(View view) {
        return mHiddenViews.contains(view);
    }

    /**
     * Marks a child view as hidden.
     *
     * @param view The view to hide.
     */
    void hide(View view) {
        final int offset = mCallback.indexOfChild(view);
        if (offset < 0) {
            throw new IllegalArgumentException("view is not a child, cannot hide " + view);
        }
        if (DEBUG && mBucket.get(offset)) {
            throw new RuntimeException("trying to hide same view twice, how come ? " + view);
        }
        mBucket.set(offset);
        hideViewInternal(view);
        if (DEBUG) {
            Log.d(TAG, "hiding child " + view + " at offset " + offset+ ", " + this);
        }
    }

    /**
     * Moves a child view from hidden list to regular list.
     * Calling this method should probably be followed by a detach, otherwise, it will suddenly
     * show up in LayoutManager's children list.
     *
     * @param view The hidden View to unhide
     */
    void unhide(View view) {
        final int offset = mCallback.indexOfChild(view);
        if (offset < 0) {
            throw new IllegalArgumentException("view is not a child, cannot hide " + view);
        }
        if (!mBucket.get(offset)) {
            throw new RuntimeException("trying to unhide a view that was not hidden" + view);
        }
        mBucket.clear(offset);
        unhideViewInternal(view);
    }

    @Override
    public String toString() {
        return mBucket.toString() + ", hidden list:" + mHiddenViews.size();
    }

    /**
     * Removes a view from the ViewGroup if it is hidden.
     *
     * @param view The view to remove.
     * @return True if the View is found and it is hidden. False otherwise.
     */
    boolean removeViewIfHidden(View view) {
        final int index = mCallback.indexOfChild(view);
        if (index == -1) {
            if (unhideViewInternal(view) && DEBUG) {
                throw new IllegalStateException("view is in hidden list but not in view group");
            }
            return true;
        }
        if (mBucket.get(index)) {
            mBucket.remove(index);
            if (!unhideViewInternal(view) && DEBUG) {
                throw new IllegalStateException(
                        "removed a hidden view but it is not in hidden views list");
            }
            mCallback.removeViewAt(index);
            return true;
        }
        return false;
    }

    /**
     * 实现了类似List<Boolean>的数据结构，从而达到减少内存占用的目的。
     * Bitset implementation that provides methods to offset indices.
     */
    static class Bucket {

        final static int BITS_PER_WORD = Long.SIZE;

        final static long LAST_BIT = 1L << (Long.SIZE - 1);

        long mData = 0;

        Bucket next;

        /**
         * 设置对应index位的bit为1(index从0开始)
           ChildHelper.Bucket bucket = new ChildHelper.Bucket();
           bucket.set(3);
           Log.i(TAG,bucket.toString());//1000

         * @param index
         */
        void set(int index) {
            if (index >= BITS_PER_WORD) {
                ensureNext();
                next.set(index - BITS_PER_WORD);
            } else {
                mData |= 1L << index;
            }
        }

        private void ensureNext() {
            if (next == null) {
                next = new Bucket();
            }
        }

        /**
         * 设置index对应的bit为0
         * @param index
         */
        void clear(int index) {
            if (index >= BITS_PER_WORD) {
                if (next != null) {
                    next.clear(index - BITS_PER_WORD);
                }
            } else {
                mData &= ~(1L << index);
            }

        }

        /**
         * 判断index对应的bit是否为1，如果为1，返回true，否则返回false。
         * @param index
         * @return
         */
        boolean get(int index) {
            if (index >= BITS_PER_WORD) {
                ensureNext();
                return next.get(index - BITS_PER_WORD);
            } else {
                return (mData & (1L << index)) != 0;
            }
        }

        /**
         * 重置，所有数据置为0
         */
        void reset() {
            mData = 0;
            if (next != null) {
                next.reset();
            }
        }

        /**
         * 在index位置插入指定bit：value为true插入1，value为false插入0
         * @param index
         * @param value
         */
        void insert(int index, boolean value) {
            if (index >= BITS_PER_WORD) {
                ensureNext();
                next.insert(index - BITS_PER_WORD, value);
            } else {
                final boolean lastBit = (mData & LAST_BIT) != 0;
                long mask = (1L << index) - 1;
                final long before = mData & mask;
                final long after = ((mData & ~mask)) << 1;
                mData = before | after;
                if (value) {
                    set(index);
                } else {
                    clear(index);
                }
                if (lastBit || next != null) {
                    ensureNext();
                    next.insert(0, lastBit);
                }
            }
        }

        /**
         * 移除index对应位置的bit。低于index的所有数据保持不变，高于index的数据右移一位。
         * @param index
         * @return
         */
        boolean remove(int index) {
            if (index >= BITS_PER_WORD) {
                ensureNext();
                return next.remove(index - BITS_PER_WORD);
            } else {
                long mask = (1L << index);
                final boolean value = (mData & mask) != 0;
                mData &= ~mask;
                mask = mask - 1;
                final long before = mData & mask;
                // cannot use >> because it adds one.
                final long after = Long.rotateRight(mData & ~mask, 1);
                mData = before | after;
                if (next != null) {
                    if (next.get(0)) {
                        set(BITS_PER_WORD - 1);
                    }
                    next.remove(0);
                }
                return value;
            }
        }

        /**
         * 计算比index小的所有位数上bit为1的总个数。
         * @param index
         * @return
         */
        int countOnesBefore(int index) {
            if (next == null) {
                if (index >= BITS_PER_WORD) {
                    return Long.bitCount(mData);
                }
                return Long.bitCount(mData & ((1L << index) - 1));
            }
            if (index < BITS_PER_WORD) {
                return Long.bitCount(mData & ((1L << index) - 1));
            } else {
                return next.countOnesBefore(index - BITS_PER_WORD) + Long.bitCount(mData);
            }
        }

        @Override
        public String toString() {
            return next == null ? Long.toBinaryString(mData)
                    : next.toString() + "xx" + Long.toBinaryString(mData);
        }
    }

    static interface Callback {

        int getChildCount();

        void addView(View child, int index);

        int indexOfChild(View view);

        void removeViewAt(int index);

        View getChildAt(int offset);

        void removeAllViews();

        RecyclerView.ViewHolder getChildViewHolder(View view);

        void attachViewToParent(View child, int index, ViewGroup.LayoutParams layoutParams);

        void detachViewFromParent(int offset);

        void onEnteredHiddenState(View child);

        void onLeftHiddenState(View child);
    }
}
