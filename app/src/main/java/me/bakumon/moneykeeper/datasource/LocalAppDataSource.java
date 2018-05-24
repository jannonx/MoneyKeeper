package me.bakumon.moneykeeper.datasource;

import android.text.TextUtils;

import com.github.mikephil.charting.data.BarEntry;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import me.bakumon.moneykeeper.App;
import me.bakumon.moneykeeper.R;
import me.bakumon.moneykeeper.database.AppDatabase;
import me.bakumon.moneykeeper.database.entity.DaySumMoneyBean;
import me.bakumon.moneykeeper.database.entity.Record;
import me.bakumon.moneykeeper.database.entity.RecordType;
import me.bakumon.moneykeeper.database.entity.RecordWithType;
import me.bakumon.moneykeeper.database.entity.SumMoneyBean;
import me.bakumon.moneykeeper.ui.addtype.TypeImgBean;
import me.bakumon.moneykeeper.utill.DateUtils;

/**
 * 数据源本地实现类
 *
 * @author Bakumon https://bakumon.me
 */
public class LocalAppDataSource implements AppDataSource {
    private final AppDatabase mAppDatabase;

    public LocalAppDataSource(AppDatabase appDatabase) {
        mAppDatabase = appDatabase;
    }

    @Override
    public Flowable<List<RecordType>> getAllRecordType() {
        return mAppDatabase.recordTypeDao().getAllRecordTypes();
    }

    @Override
    public Completable initRecordTypes() {
        return Completable.fromAction(() -> {
            if (mAppDatabase.recordTypeDao().getRecordTypeCount() < 1) {
                // 没有记账类型数据记录，插入默认的数据类型
                mAppDatabase.recordTypeDao().insertRecordTypes(RecordTypeInitCreator.createRecordTypeData());
            }
        });
    }

    @Override
    public Completable deleteRecord(Record record) {
        return Completable.fromAction(() -> mAppDatabase.recordDao().deleteRecord(record));
    }

    @Override
    public Flowable<List<RecordWithType>> getCurrentMonthRecordWithTypes() {
        Date dateFrom = DateUtils.getCurrentMonthStart();
        Date dateTo = DateUtils.getCurrentMonthEnd();
        return mAppDatabase.recordDao().getRangeRecordWithTypes(dateFrom, dateTo);
    }

    @Override
    public Flowable<List<RecordWithType>> getRecordWithTypes(Date dateFrom, Date dateTo, int type) {
        return mAppDatabase.recordDao().getRangeRecordWithTypes(dateFrom, dateTo, type);
    }

    @Override
    public Completable insertRecord(Record record) {
        return Completable.fromAction(() -> mAppDatabase.recordDao().insertRecord(record));
    }

    @Override
    public Completable updateRecord(Record record) {
        return Completable.fromAction(() -> mAppDatabase.recordDao().updateRecords(record));
    }

    @Override
    public Completable updateRecordTypes(RecordType... recordTypes) {
        return Completable.fromAction(() -> mAppDatabase.recordTypeDao().updateRecordTypes(recordTypes));
    }

    @Override
    public Completable sortRecordTypes(List<RecordType> recordTypes) {
        return Completable.fromAction(() -> {
            if (recordTypes != null && recordTypes.size() > 1) {
                List<RecordType> sortTypes = new ArrayList<>();
                for (int i = 0; i < recordTypes.size(); i++) {
                    RecordType type = recordTypes.get(i);
                    if (type.ranking != i) {
                        type.ranking = i;
                        sortTypes.add(type);
                    }
                }
                RecordType[] typeArray = new RecordType[sortTypes.size()];
                mAppDatabase.recordTypeDao().updateRecordTypes(typeArray);
            }
        });
    }

    @Override
    public Completable deleteRecordType(RecordType recordType) {
        return Completable.fromAction(() -> {
            if (mAppDatabase.recordDao().getRecordCountWithTypeId(recordType.id) > 0) {
                recordType.state = RecordType.STATE_DELETED;
                mAppDatabase.recordTypeDao().updateRecordTypes(recordType);
            } else {
                mAppDatabase.recordTypeDao().deleteRecordType(recordType);
            }
        });
    }

    @Override
    public Flowable<List<RecordType>> getRecordTypes(int type) {
        return mAppDatabase.recordTypeDao().getRecordTypes(type);
    }

    @Override
    public Flowable<List<TypeImgBean>> getAllTypeImgBeans(int type) {
        return Flowable.create(e -> {
            List<TypeImgBean> beans = TypeImgListCreator.createTypeImgBeanData(type);
            e.onNext(beans);
            e.onComplete();
        }, BackpressureStrategy.BUFFER);
    }

    @Override
    public Completable addRecordType(int type, String imgName, String name) {
        return Completable.fromAction(() -> {
            RecordType recordType = mAppDatabase.recordTypeDao().getTypeByName(type, name);
            if (recordType != null) {
                // name 类型存在
                if (recordType.state == RecordType.STATE_DELETED) {
                    // 已删除状态
                    recordType.state = RecordType.STATE_NORMAL;
                    recordType.ranking = System.currentTimeMillis();
                    recordType.imgName = imgName;
                    mAppDatabase.recordTypeDao().updateRecordTypes(recordType);
                } else {
                    // 提示用户该类型已经存在
                    throw new IllegalStateException(name + App.getINSTANCE().getString(R.string.toast_type_is_exist));
                }
            } else {
                // 不存在，直接新增
                RecordType insertType = new RecordType(name, imgName, type, System.currentTimeMillis());
                mAppDatabase.recordTypeDao().insertRecordTypes(insertType);
            }
        });
    }

    @Override
    public Completable updateRecordType(RecordType oldRecordType, RecordType recordType) {
        return Completable.fromAction(() -> {
            String oldName = oldRecordType.name;
            String oldImgName = oldRecordType.imgName;
            if (!TextUtils.equals(oldName, recordType.name)) {
                RecordType recordTypeFromDb = mAppDatabase.recordTypeDao().getTypeByName(recordType.type, recordType.name);
                if (recordTypeFromDb != null) {
                    if (recordTypeFromDb.state == RecordType.STATE_DELETED) {

                        // 1。recordTypeFromDb 改成正常状态，name改成recordType#name，imageName同理
                        // 2。更新 recordTypeFromDb
                        // 3。判断是否有 oldRecordType 类型的 record 记录
                        // 4。如果有记录，把这些记录的 type_id 改成 recordTypeFromDb.id
                        // 5。删除 oldRecordType 记录

                        recordTypeFromDb.state = RecordType.STATE_NORMAL;
                        recordTypeFromDb.name = recordType.name;
                        recordTypeFromDb.imgName = recordType.imgName;
                        recordTypeFromDb.ranking = System.currentTimeMillis();

                        mAppDatabase.recordTypeDao().updateRecordTypes(recordTypeFromDb);

                        List<Record> recordsWithOldType = mAppDatabase.recordDao().getRecordsWithTypeId(oldRecordType.id);
                        if (recordsWithOldType != null && recordsWithOldType.size() > 0) {
                            for (Record record : recordsWithOldType) {
                                record.recordTypeId = recordTypeFromDb.id;
                            }
                            mAppDatabase.recordDao().updateRecords(recordsWithOldType.toArray(new Record[recordsWithOldType.size()]));
                        }

                        mAppDatabase.recordTypeDao().deleteRecordType(oldRecordType);
                    } else {
                        // 提示用户该类型已经存在
                        throw new IllegalStateException(recordType.name + App.getINSTANCE().getString(R.string.toast_type_is_exist));
                    }
                } else {
                    mAppDatabase.recordTypeDao().updateRecordTypes(recordType);
                }
            } else if (!TextUtils.equals(oldImgName, recordType.imgName)) {
                mAppDatabase.recordTypeDao().updateRecordTypes(recordType);
            }
        });
    }

    @Override
    public Flowable<List<SumMoneyBean>> getCurrentMonthSumMoney() {
        Date dateFrom = DateUtils.getCurrentMonthStart();
        Date dateTo = DateUtils.getCurrentMonthEnd();
        return mAppDatabase.recordDao().getSumMoney(dateFrom, dateTo);
    }

    @Override
    public Flowable<List<BarEntry>> getDaySumMoney(int year, int month, int type) {
        return Flowable.create(e -> {
            Date dateFrom = DateUtils.getMonthStart(year, month);
            Date dateTo = DateUtils.getMonthEnd(year, month);
            List<DaySumMoneyBean> moneyBeans = mAppDatabase.recordDao().getDaySumMoney(dateFrom, dateTo, type);

            if (moneyBeans != null && moneyBeans.size() > 1) {
                int days = DateUtils.getDayCount(year, month);

                List<BarEntry> entryList = new ArrayList<>();

                BarEntry barEntry;

                for (int i = 0; i < days; i++) {
                    for (int j = 0; j < moneyBeans.size(); j++) {
                        if (i + 1 == moneyBeans.get(j).time.getDate()) {
                            barEntry = new BarEntry(i + 1, Float.parseFloat(moneyBeans.get(j).daySumMoney));
                            entryList.add(barEntry);
                        }
                    }
                    barEntry = new BarEntry(i + 1, 0);
                    entryList.add(barEntry);
                }
                e.onNext(entryList);
            }
            e.onComplete();
        }, BackpressureStrategy.BUFFER);
    }
}
