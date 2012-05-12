package org.mariotaku.twidere.fragment;

import static org.mariotaku.twidere.util.Utils.buildActivatedStatsWhereClause;
import static org.mariotaku.twidere.util.Utils.buildFilterWhereClause;
import static org.mariotaku.twidere.util.Utils.getActivatedAccounts;
import static org.mariotaku.twidere.util.Utils.getLastStatusIds;
import static org.mariotaku.twidere.util.Utils.getMentionedNames;
import static org.mariotaku.twidere.util.Utils.getTableId;
import static org.mariotaku.twidere.util.Utils.getTableNameForContentUri;
import static org.mariotaku.twidere.util.Utils.setMenuForStatus;

import java.util.List;

import org.mariotaku.twidere.R;
import org.mariotaku.twidere.adapter.ParcelableStatusesAdapter;
import org.mariotaku.twidere.app.TwidereApplication;
import org.mariotaku.twidere.loader.CursorToStatusesLoader;
import org.mariotaku.twidere.provider.TweetStore.Statuses;
import org.mariotaku.twidere.util.AsyncTaskManager;
import org.mariotaku.twidere.util.LazyImageLoader;
import org.mariotaku.twidere.util.ParcelableStatus;
import org.mariotaku.twidere.util.ServiceInterface;
import org.mariotaku.twidere.util.StatusViewHolder;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;

import com.actionbarsherlock.view.ActionMode;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.handmark.pulltorefresh.library.PullToRefreshBase.OnRefreshListener;
import com.handmark.pulltorefresh.library.PullToRefreshListView;

public abstract class StatusesFragment extends BaseFragment implements OnRefreshListener,
		LoaderCallbacks<List<ParcelableStatus>>, OnScrollListener, OnItemClickListener, OnItemLongClickListener,
		ActionMode.Callback {

	public ServiceInterface mServiceInterface;
	public PullToRefreshListView mListView;
	public ContentResolver mResolver;
	private SharedPreferences mPreferences;
	private AsyncTaskManager mAsyncTaskManager;
	private ParcelableStatusesAdapter mAdapter;

	private Handler mHandler;
	private Runnable mTicker;

	public ParcelableStatus mSelectedStatus;
	private int mRunningTaskId;
	private boolean mBusy, mTickerStopped;
	private boolean mDisplayProfileImage, mDisplayName, mReachedBottom, mActivityFirstCreated;
	private float mTextSize;
	private boolean mLoadMoreAutomatically, mNotReachedBottomBefore = true;

	public abstract Uri getContentUri();

	@Override
	public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
		if (mSelectedStatus != null) {
			long status_id = mSelectedStatus.status_id;
			String text_plain = mSelectedStatus.text_plain;
			String screen_name = mSelectedStatus.screen_name;
			String name = mSelectedStatus.name;
			long account_id = mSelectedStatus.account_id;
			switch (item.getItemId()) {
				case MENU_SHARE: {
					Intent intent = new Intent(Intent.ACTION_SEND);
					intent.setType("text/plain");
					intent.putExtra(Intent.EXTRA_TEXT, "@" + screen_name + ": " + text_plain);
					startActivity(Intent.createChooser(intent, getString(R.string.share)));
					break;
				}
				case MENU_RETWEET: {
					mServiceInterface.retweetStatus(new long[] { account_id }, status_id);
					break;
				}
				case MENU_QUOTE: {
					Intent intent = new Intent(INTENT_ACTION_COMPOSE);
					Bundle bundle = new Bundle();
					bundle.putLong(INTENT_KEY_ACCOUNT_ID, account_id);
					bundle.putLong(INTENT_KEY_IN_REPLY_TO_ID, status_id);
					bundle.putString(INTENT_KEY_IN_REPLY_TO_SCREEN_NAME, screen_name);
					bundle.putString(INTENT_KEY_IN_REPLY_TO_NAME, name);
					bundle.putBoolean(INTENT_KEY_IS_QUOTE, true);
					bundle.putString(INTENT_KEY_TEXT, "RT @" + screen_name + ": " + text_plain);
					intent.putExtras(bundle);
					startActivity(intent);
					break;
				}
				case MENU_REPLY: {
					Intent intent = new Intent(INTENT_ACTION_COMPOSE);
					Bundle bundle = new Bundle();
					bundle.putStringArray(INTENT_KEY_MENTIONS, getMentionedNames(screen_name, text_plain, false, true));
					bundle.putLong(INTENT_KEY_ACCOUNT_ID, account_id);
					bundle.putLong(INTENT_KEY_IN_REPLY_TO_ID, status_id);
					bundle.putString(INTENT_KEY_IN_REPLY_TO_SCREEN_NAME, screen_name);
					bundle.putString(INTENT_KEY_IN_REPLY_TO_NAME, name);
					intent.putExtras(bundle);
					startActivity(intent);
					break;
				}
				case MENU_FAV: {
					if (mSelectedStatus.is_favorite) {
						mServiceInterface.destroyFavorite(new long[] { account_id }, status_id);
					} else {
						mServiceInterface.createFavorite(new long[] { account_id }, status_id);
					}
					break;
				}
				case MENU_DELETE: {
					mServiceInterface.destroyStatus(account_id, status_id);
					break;
				}
				default:
					return false;
			}
		}
		mode.finish();
		return true;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		mAsyncTaskManager = AsyncTaskManager.getInstance();
		mPreferences = getSherlockActivity().getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
		mResolver = getSherlockActivity().getContentResolver();
		mDisplayProfileImage = mPreferences.getBoolean(PREFERENCE_KEY_DISPLAY_PROFILE_IMAGE, true);
		mDisplayName = mPreferences.getBoolean(PREFERENCE_KEY_DISPLAY_NAME, true);
		mTextSize = mPreferences.getFloat(PREFERENCE_KEY_TEXT_SIZE, PREFERENCE_DEFAULT_TEXT_SIZE);
		mServiceInterface = ((TwidereApplication) getSherlockActivity().getApplication()).getServiceInterface();
		LazyImageLoader imageloader = ((TwidereApplication) getSherlockActivity().getApplication())
				.getListProfileImageLoader();
		mAdapter = new ParcelableStatusesAdapter(getSherlockActivity(), imageloader);
		mListView = (PullToRefreshListView) getView().findViewById(R.id.refreshable_list);
		mListView.setOnRefreshListener(this);
		ListView list = mListView.getRefreshableView();
		list.setAdapter(mAdapter);
		list.setOnScrollListener(this);
		list.setOnItemClickListener(this);
		list.setOnItemLongClickListener(this);
		getLoaderManager().initLoader(0, null, this);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		mActivityFirstCreated = true;
		// Tell the framework to try to keep this fragment around
		// during a configuration change.
		setRetainInstance(true);
	}

	@Override
	public boolean onCreateActionMode(ActionMode mode, Menu menu) {
		getSherlockActivity().getSupportMenuInflater().inflate(R.menu.action_status, menu);
		return true;
	}

	@Override
	public Loader<List<ParcelableStatus>> onCreateLoader(int id, Bundle args) {
		String[] cols = Statuses.COLUMNS;
		Uri uri = getContentUri();
		String where = buildActivatedStatsWhereClause(getSherlockActivity(), null);
		if (mPreferences.getBoolean(PREFERENCE_KEY_ENABLE_FILTER, false)) {
			String table = getTableNameForContentUri(uri);
			where = buildFilterWhereClause(table, where);
		}
		return new CursorToStatusesLoader(getSherlockActivity(), uri, cols, where, null, Statuses.DEFAULT_SORT_ORDER);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		return inflater.inflate(R.layout.refreshable_list, container, false);
	}

	@Override
	public void onDestroy() {
		mActivityFirstCreated = true;
		super.onDestroy();
	}

	@Override
	public void onDestroyActionMode(ActionMode mode) {

	}

	@Override
	public void onItemClick(AdapterView<?> adapter, View view, int position, long id) {
		Object tag = view.getTag();
		if (tag instanceof StatusViewHolder) {
			ParcelableStatus status = mAdapter.findItemById(id);
			StatusViewHolder holder = (StatusViewHolder) tag;
			if (holder.show_as_gap || position == adapter.getCount() - 1 && !mLoadMoreAutomatically) {
				getStatuses(new long[] { status.account_id }, new long[] { status.status_id });
			} else {
				Uri.Builder builder = new Uri.Builder();
				builder.scheme(SCHEME_TWIDERE);
				builder.authority(AUTHORITY_STATUS);
				builder.appendQueryParameter(QUERY_PARAM_ACCOUNT_ID, String.valueOf(status.account_id));
				builder.appendQueryParameter(QUERY_PARAM_STATUS_ID, String.valueOf(status.status_id));
				startActivity(new Intent(Intent.ACTION_DEFAULT, builder.build()));
			}
		}
	}

	@Override
	public boolean onItemLongClick(AdapterView<?> adapter, View view, int position, long id) {
		Object tag = view.getTag();
		if (tag instanceof StatusViewHolder) {
			StatusViewHolder holder = (StatusViewHolder) tag;
			if (holder.show_as_gap) return false;
			mSelectedStatus = mAdapter.findItemById(id);
			getSherlockActivity().startActionMode(this);
			return true;
		}
		return false;
	}

	@Override
	public void onLoaderReset(Loader<List<ParcelableStatus>> loader) {
		mAdapter.changeData(null);
	}

	@Override
	public void onLoadFinished(Loader<List<ParcelableStatus>> loader, List<ParcelableStatus> data) {
		mAdapter.changeData(data);
	}

	@Override
	public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
		setMenuForStatus(getSherlockActivity(), menu, mSelectedStatus);
		return true;
	}

	@Override
	public void onRefresh() {

		long[] account_ids = getActivatedAccounts(getSherlockActivity());
		mRunningTaskId = getStatuses(account_ids, null);

	}

	@Override
	public void onResume() {
		super.onResume();
		boolean display_profile_image = mPreferences.getBoolean(PREFERENCE_KEY_DISPLAY_PROFILE_IMAGE, true);
		boolean display_name = mPreferences.getBoolean(PREFERENCE_KEY_DISPLAY_NAME, true);
		float text_size = mPreferences.getFloat(PREFERENCE_KEY_TEXT_SIZE, PREFERENCE_DEFAULT_TEXT_SIZE);
		mLoadMoreAutomatically = mPreferences.getBoolean(PREFERENCE_LOAD_MORE_AUTOMATICALLY, false);
		mAdapter.setShowLastItemAsGap(!mLoadMoreAutomatically);
		mAdapter.setDisplayProfileImage(display_profile_image);
		mAdapter.setDisplayName(display_name);
		mAdapter.setStatusesTextSize(text_size);
		if (mDisplayProfileImage != display_profile_image || mDisplayName != display_name || mTextSize != text_size) {
			mDisplayProfileImage = display_profile_image;
			mDisplayName = display_name;
			mTextSize = text_size;
			mListView.getRefreshableView().invalidateViews();
		}
	}

	@Override
	public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
		boolean reached = firstVisibleItem + visibleItemCount >= totalItemCount && totalItemCount >= visibleItemCount;

		if (mReachedBottom != reached) {
			mReachedBottom = reached;
			if (mReachedBottom && mNotReachedBottomBefore) {
				mNotReachedBottomBefore = false;
				return;
			}
			if (mLoadMoreAutomatically && mReachedBottom) {
				if (!mAsyncTaskManager.isExcuting(mRunningTaskId)) {
					mRunningTaskId = getStatuses(getActivatedAccounts(getSherlockActivity()),
							getLastStatusIds(getSherlockActivity(), getContentUri()));
				}
			}
		}

	}

	@Override
	public void onScrollStateChanged(AbsListView view, int scrollState) {
		switch (scrollState) {
			case SCROLL_STATE_FLING:
			case SCROLL_STATE_TOUCH_SCROLL:
				mBusy = true;
				break;
			case SCROLL_STATE_IDLE:
				mBusy = false;
				break;
		}

	}

	@Override
	public void onStart() {
		super.onStart();
		mTickerStopped = false;
		mHandler = new Handler();

		if (!mAsyncTaskManager.isExcuting(mRunningTaskId)) {
			mListView.onRefreshComplete();
		}

		mTicker = new Runnable() {

			@Override
			public void run() {
				if (mTickerStopped) return;
				if (mListView != null && !mBusy) {
					mListView.getRefreshableView().invalidateViews();
				}
				final long now = SystemClock.uptimeMillis();
				final long next = now + 1000 - now % 1000;
				mHandler.postAtTime(mTicker, next);
			}
		};
		mTicker.run();

		if (!mActivityFirstCreated) {
			getLoaderManager().restartLoader(0, null, this);
		}
	}

	@Override
	public void onStop() {
		mTickerStopped = true;
		mActivityFirstCreated = false;
		super.onStop();
	}

	private int getStatuses(long[] account_ids, long[] max_ids) {
		switch (getTableId(getContentUri())) {
			case URI_STATUSES:
				return mServiceInterface.getHomeTimeline(account_ids, max_ids);
			case URI_MENTIONS:
				return mServiceInterface.getMentions(account_ids, max_ids);
			case URI_FAVORITES:
				break;
		}
		return 0;
	}
}