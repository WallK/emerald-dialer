package ru.henridellal.dialer;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.PhoneNumberUtils;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;

import com.pinyinsearch.model.PinyinSearchUnit;
import com.pinyinsearch.util.PinyinUtil;
import com.pinyinsearch.util.T9Util;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.*;

import ru.henridellal.dialer.AsyncContactImageLoader.ImageCallback;

public class ContactsEntryAdapter extends BaseAdapter implements Filterable, View.OnClickListener
{
	public static final String[] PROJECTION = {
		Phone._ID,
		Phone.LOOKUP_KEY,
		Phone.DISPLAY_NAME,
		Phone.NUMBER
	};
	
	private static final int[] t9NumberPatternIds = new int[]{
				R.string.regex_0, R.string.regex_1,
				R.string.regex_2, R.string.regex_3,
				R.string.regex_4, R.string.regex_5,
				R.string.regex_6, R.string.regex_7,
				R.string.regex_8, R.string.regex_9 };
	
	private static final int COLUMN_LOOKUP_KEY = 1;
	private static final int COLUMN_NAME = 2;
	private static final int COLUMN_NUMBER = 3;

	public static final int FILTERING_MODE_REGEX = 0;
	public static final int FILTERING_MODE_RAW = 1;
	public static final int FILTERING_MODE_PINYIN = 2;
	private int filteringMode;

	private ForegroundColorSpan span;
	private StyleSpan boldStyleSpan;
	
	private Map<Character, String> t9NumberPatterns;
	private ArrayList<RegexQueryResult> regexQueryResults;
	private AsyncContactImageLoader mAsyncContactImageLoader;
	private Cursor mCursor;
	private ContactsFilter mFilter;
	private Locale t9Locale;
	private SoftReference<DialerActivity> activityRef;
	
	public ContactsEntryAdapter(DialerActivity activity, AsyncContactImageLoader asyncContactImageLoader, Context t9LocaleContext, Locale t9Locale) {
		super();
		activityRef = new SoftReference<DialerActivity>(activity);
		initT9NumberPatterns(null != t9LocaleContext ?
				t9LocaleContext.getResources()
				: activityRef.get().getResources());
		regexQueryResults = new ArrayList<RegexQueryResult>();
		mAsyncContactImageLoader = asyncContactImageLoader;
		span = new ForegroundColorSpan(activity.getResources().getColor(R.color.green_600));
		boldStyleSpan = new StyleSpan(Typeface.BOLD);
		this.t9Locale = t9Locale;
	}
	
	private void initT9NumberPatterns(Resources res) {
		t9NumberPatterns = new HashMap<Character, String>();
		for (char i = '0'; i <= '9'; i++) {
			t9NumberPatterns.put(new Character(i), res.getString(t9NumberPatternIds[Character.getNumericValue(i)]));
		}
		t9NumberPatterns.put(new Character('*'), res.getString(R.string.regex_star));
		t9NumberPatterns.put(new Character('#'), res.getString(R.string.regex_hash));
		t9NumberPatterns.put(new Character('+'), Pattern.quote("+"));
	}
	
	public void setCursor(Cursor cursor) {
		if (mCursor != null) {
			mCursor.close();
		}
		mCursor = cursor;
	}
	
	@Override
	public Filter getFilter() {
		if (mFilter == null) {
			mFilter = new ContactsFilter();
		}
		return mFilter;
	}
	
	public void resetFilter() {
		mFilter = null;
		if (null != regexQueryResults)
			regexQueryResults.clear();
	}
	@Override
	public int getCount() {
		return (null != regexQueryResults) ? regexQueryResults.size() : 0;
	}

	@Override
	public Object getItem(int position) {
		return null;
	}
	
	@Override
	public long getItemId(int position) {
		return 0;
	}
	
	@Override
	public void onClick(View view) {
		if (view.getId() == R.id.contact_entry_image) {
			Intent intent = new Intent(Intent.ACTION_VIEW);
			Uri uri = Uri.withAppendedPath(Phone.CONTENT_URI, ((ContactImageTag)view.getTag()).contactId);
			intent.setDataAndType(uri, "vnd.android.cursor.dir/contact");
			try {
				activityRef.get().startActivity(intent);
			} catch (ActivityNotFoundException e) {
				activityRef.get().showMissingContactsAppDialog();
			}
		}
	}
	
	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		View view;
		
		if (convertView == null) {
			view = LayoutInflater.from(activityRef.get()).inflate(R.layout.contacts_entry, parent, false);
			view.setTag(new ContactsEntryCache(view));
		} else {
			view = convertView;
		}
		final RegexQueryResult queryResult = regexQueryResults.get(position);
		final ContactsEntryCache viewCache = (ContactsEntryCache) view.getTag();
		mCursor.moveToPosition(queryResult.position);
		String name = mCursor.getString(COLUMN_NAME);
		if (!TextUtils.isEmpty(name) && queryResult.start != queryResult.end) {
			SpannableString nameSpanned = new SpannableString(name);
			nameSpanned.setSpan(span, queryResult.start, queryResult.end, 0);
			nameSpanned.setSpan(boldStyleSpan, queryResult.start, queryResult.end, 0);
			viewCache.contactName.setText(nameSpanned);
		} else {
			viewCache.contactName.setText(name);
		}
		String phoneNumber = mCursor.getString(COLUMN_NUMBER);
		if (null != phoneNumber && !TextUtils.isEmpty(phoneNumber) && queryResult.numberStart != queryResult.numberEnd) {
			SpannableString numberSpanned = new SpannableString(formatNumber(phoneNumber));
			if (queryResult.numberEnd <= numberSpanned.length())
				numberSpanned.setSpan(span, queryResult.numberStart, queryResult.numberEnd, 0);
			viewCache.phoneNumber.setText(numberSpanned);
		} else {
			viewCache.phoneNumber.setText(formatNumber(phoneNumber));
		}
		
		String lookupKey = mCursor.getString(COLUMN_LOOKUP_KEY);
		ContactImageTag tag = new ContactImageTag(String.valueOf(mCursor.getInt(0)), lookupKey);
		viewCache.contactImage.setTag(tag); // set a tag for the callback to be able to check, so we don't set the contact image of a reused view
		Drawable d = mAsyncContactImageLoader.loadDrawableForContact(lookupKey, new ImageCallback() {
			
			@Override
			public void imageLoaded(Drawable imageDrawable, String lookupKey) {
				if (lookupKey.equals(((ContactImageTag)viewCache.contactImage.getTag()).lookupKey)) {
					viewCache.contactImage.setImageDrawable(imageDrawable);
				}
			}
		});
		viewCache.contactImage.setImageDrawable(d);
		viewCache.contactImage.setOnClickListener(this);
		return view;
	}
	
	public void setFilteringMode(int filteringMode) {
		this.filteringMode = filteringMode;
	}
	
	public String getPhoneNumber(int position) {
		mCursor.moveToPosition(regexQueryResults.get(position).position);
		return mCursor.getString(COLUMN_NUMBER);
	}
	
	public String formatNumber(String number) {
		PhoneNumberUtils.normalizeNumber(number);
		String result = PhoneNumberUtils.formatNumber(number, t9Locale.getCountry());
		return (null != result) ? result : number;
	}
	
	public String formContactNameRegex(String s) {
		StringBuilder result = new StringBuilder();
		char mChar;
		for (int i = 0; i < s.length(); i++) {
			mChar = s.charAt(i);
			result.append(t9NumberPatterns.get(mChar));
		}
		return result.toString();
	}
	
	private boolean isRegexIdentifier(char c) {
		return c == '*' || c == '#' || c == '+';
	}
	
	public String formNumberRegex(String s) {
		StringBuilder result = new StringBuilder();
		char numberChar = s.charAt(0);
		result.append(isRegexIdentifier(numberChar)? Pattern.quote(Character.toString(numberChar)) : numberChar);
		for (int i = 1; i < s.length(); i++) {
			result.append("[\\W]*");
			numberChar = s.charAt(i);
			result.append(isRegexIdentifier(numberChar) ? Pattern.quote(Character.toString(numberChar)) : numberChar);
		}
		return result.toString();
	}
	
	private class ContactsFilter extends Filter {

		private void filterPinyin(ArrayList<RegexQueryResult> resultsList, CharSequence constraint) {
			mCursor.moveToFirst();
			String constraintString = constraint.toString().toLowerCase();
			while (!mCursor.isAfterLast()) {
				String name = mCursor.getString(COLUMN_NAME);
				String number = mCursor.getString(COLUMN_NUMBER);
				RegexQueryResult queryResult = null;
				if (null != name) {
					PinyinSearchUnit psu = new PinyinSearchUnit(name);
					PinyinUtil.parse(psu);
					T9Util.match(psu, constraintString);
					String keyword = psu.getMatchKeyword().toString();

					int pinyinIndexOfConstraint = TextUtils.isEmpty(keyword) ? -1 : name.indexOf(keyword);

					if (pinyinIndexOfConstraint != -1) {
						queryResult = new RegexQueryResult(mCursor.getPosition(), pinyinIndexOfConstraint, pinyinIndexOfConstraint+keyword.length());
					}

				}
				if (null != number) {
					number = number.toLowerCase();
					int numberIndexOfConstraint = number.indexOf(constraintString);
					if (numberIndexOfConstraint != -1) {
						if (null == queryResult) {
							queryResult = new RegexQueryResult(mCursor.getPosition(), Integer.MAX_VALUE, Integer.MAX_VALUE);
						}
						queryResult.setNumberPlace(numberIndexOfConstraint, numberIndexOfConstraint+constraintString.length());
					}

				}
				if (null != queryResult) {
					resultsList.add(queryResult);
				}
				mCursor.moveToNext();
			}
		}

		private void filterRaw(ArrayList<RegexQueryResult> resultsList, CharSequence constraint) {
			mCursor.moveToFirst();
			String constraintString = constraint.toString().toLowerCase();
			while (!mCursor.isAfterLast()) {
				String name = mCursor.getString(COLUMN_NAME);
				String number = mCursor.getString(COLUMN_NUMBER);
				RegexQueryResult queryResult = null;
				if (null != name) {
					name = name.toLowerCase();
					int nameIndexOfConstraint = name.indexOf(constraintString);
					if (nameIndexOfConstraint != -1)
						queryResult = new RegexQueryResult(mCursor.getPosition(), nameIndexOfConstraint, nameIndexOfConstraint+constraintString.length());
				}
				if (null != number) {
					number = number.toLowerCase();
					int numberIndexOfConstraint = number.indexOf(constraintString);
					if (numberIndexOfConstraint != -1) {
						if (null == queryResult) {
							queryResult = new RegexQueryResult(mCursor.getPosition(), 0, 0);
						}
						queryResult.setNumberPlace(numberIndexOfConstraint, numberIndexOfConstraint+constraintString.length());
					}
				}
				
				if (null != queryResult) {
					resultsList.add(queryResult);
				}
				mCursor.moveToNext();
			}
		}
		
		private void filterWithRegex(ArrayList<RegexQueryResult> resultsList, CharSequence constraint) {
			String nameRegex = formContactNameRegex(constraint.toString());
			String numberRegex = formNumberRegex(constraint.toString());
			Pattern namePattern = Pattern.compile(nameRegex);
			Pattern wordStartPattern = Pattern.compile("\\s+" + nameRegex);
			Pattern numberPattern = Pattern.compile(numberRegex);
			mCursor.moveToFirst();
			while (!mCursor.isAfterLast()) {
				String name = mCursor.getString(COLUMN_NAME);
				String number = mCursor.getString(COLUMN_NUMBER);
				Matcher nameMatcher = (null != name) ? namePattern.matcher(name) : null;
				Matcher numberMatcher = (null != number) ? numberPattern.matcher(formatNumber(number)) : null;
				RegexQueryResult queryResult = null;
				if (null != nameMatcher && nameMatcher.find()) {
					if (nameMatcher.start() == 0) {
						queryResult = new RegexQueryResult( mCursor.getPosition(), nameMatcher.start(), nameMatcher.end());
					} else {
						Matcher wordStartMatcher = wordStartPattern.matcher(name);
						if (wordStartMatcher.find()) {
							queryResult = new RegexQueryResult( mCursor.getPosition(), wordStartMatcher.start(), wordStartMatcher.end());
						}
					}
				}
				if (null != numberMatcher && numberMatcher.find()) {
					if (null == queryResult) {
						queryResult = new RegexQueryResult( mCursor.getPosition(), 256+numberMatcher.start(), 256+numberMatcher.start());
					}
					queryResult.setNumberPlace(numberMatcher.start(), numberMatcher.end());
				}
				if (null != queryResult) {
					resultsList.add(queryResult);
				}
				mCursor.moveToNext();
			}
		}
		
		@Override
		protected Filter.FilterResults performFiltering(CharSequence constraint) {
			Filter.FilterResults results = new FilterResults();
			ArrayList<RegexQueryResult> resultsList = new ArrayList<RegexQueryResult>();
			switch (filteringMode) {
				case FILTERING_MODE_RAW:
					filterRaw(resultsList, constraint);
					break;
				case FILTERING_MODE_PINYIN:
					filterPinyin(resultsList, constraint);
					break;
				case FILTERING_MODE_REGEX:
					filterWithRegex(resultsList, constraint);
					break;
			}
			Collections.sort(resultsList);
			results.values = resultsList;
			results.count = resultsList.size();
			return results;
		}
		
		protected void publishResults(CharSequence constraint, Filter.FilterResults results) {
			regexQueryResults = ((ArrayList<RegexQueryResult>) results.values);
			notifyDataSetChanged();
		}
	
	}
	
}
