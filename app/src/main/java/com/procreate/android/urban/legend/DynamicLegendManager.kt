package com.procreate.android.urban.legend

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.procreate.android.urban.model.LegendItem
import com.procreate.android.urban.model.UrbanElement
import com.procreate.android.urban.model.UrbanToolType

/**
 * مدير مفتاح الخريطة التفاعلي والديناميكي (Dynamic Map Legend Manager).
 * يراقب العناصر المستخدمة على اللوحة تلقائياً:
 * - كلما استخدم المستخدم أداة (محور، ساحة، رمز، ممر)، تُضاف تلقائياً إلى مفتاح الخريطة.
 * - يجمع عدد التكرارات، وإجمالي المساحات (م²)، وإجمالي الأطوال (م).
 * - يتيح للمستخدم تعديل التسميات أو استبعاد أي عنصر من المفتاح النهائي.
 */
class DynamicLegendManager {

    private val _legendItems = MutableLiveData<MutableList<LegendItem>>(mutableListOf())
    val legendItems: LiveData<MutableList<LegendItem>> = _legendItems

    /** إشعار المستخدم عند إضافة عنصر جديد للمفتاح لأول مرة */
    var onNewLegendItemDiscovered: ((LegendItem) -> Unit)? = null

    /**
     * تسجيل عنصر تم رسمه على اللوحة
     */
    fun registerElement(element: UrbanElement) {
        val currentList = _legendItems.value ?: mutableListOf()
        val existing = currentList.find { it.toolType == element.toolType }

        if (existing != null) {
            existing.count++
            when (element) {
                is UrbanElement.ArrowPath -> existing.totalLengthMeters += element.lengthMeters
                is UrbanElement.HatchPolygon -> existing.totalAreaSqMeters += element.areaSqMeters
                is UrbanElement.BoundaryPath -> existing.totalLengthMeters += element.lengthMeters
                else -> {}
            }
        } else {
            val newItem = LegendItem(
                id = element.toolType.name,
                nameAr = element.toolType.titleAr,
                nameEn = element.toolType.titleEn,
                toolType = element.toolType,
                color = element.color,
                count = 1,
                totalLengthMeters = when (element) {
                    is UrbanElement.ArrowPath -> element.lengthMeters
                    is UrbanElement.BoundaryPath -> element.lengthMeters
                    else -> 0f
                },
                totalAreaSqMeters = when (element) {
                    is UrbanElement.HatchPolygon -> element.areaSqMeters
                    else -> 0f
                }
            )
            currentList.add(newItem)
            onNewLegendItemDiscovered?.invoke(newItem)
        }

        _legendItems.postValue(currentList)
    }

    /**
     * إعادة بناء مفتاح الخريطة بالكامل من القائمة الحالية للعناصر المرسومة - على عكس
     * registerElement الذي يراكم القيم فقط لحظة الإضافة، هذا يعيد حساب العدد والطول/المساحة
     * الإجمالية من الصفر في كل مرة. يُستخدم بعد أي تعديل على عنصر موجود (تحريك، تغيير حجم،
     * إضافة/حذف نقطة) أو حذفه، حتى لا تبقى أرقام المفتاح والجداول من لحظة الرسم الأولى فقط.
     * يحافظ على أي تسمية مخصصة أو حالة إخفاء ضبطها المستخدم يدوياً لنفس نوع الأداة.
     */
    fun recomputeFromElements(elements: List<UrbanElement>) {
        val previous = _legendItems.value ?: mutableListOf()
        val rebuilt = mutableListOf<LegendItem>()
        val grouped = elements.groupBy { it.toolType }
        for ((toolType, group) in grouped) {
            val prior = previous.find { it.toolType == toolType }
            var length = 0f
            var area = 0f
            for (element in group) {
                when (element) {
                    is UrbanElement.ArrowPath -> length += element.lengthMeters
                    is UrbanElement.BoundaryPath -> length += element.lengthMeters
                    is UrbanElement.HatchPolygon -> area += element.areaSqMeters
                    else -> {}
                }
            }
            rebuilt.add(
                LegendItem(
                    id = toolType.name,
                    nameAr = prior?.nameAr ?: toolType.titleAr,
                    nameEn = prior?.nameEn ?: toolType.titleEn,
                    toolType = toolType,
                    color = group.last().color,
                    count = group.size,
                    totalLengthMeters = length,
                    totalAreaSqMeters = area,
                    isVisibleInLegend = prior?.isVisibleInLegend ?: true
                )
            )
        }
        _legendItems.postValue(rebuilt)
    }

    /**
     * حذف عنصر من مفتاح الخريطة
     */
    fun removeItem(toolType: UrbanToolType) {
        val currentList = _legendItems.value ?: return
        currentList.removeAll { it.toolType == toolType }
        _legendItems.postValue(currentList)
    }

    /**
     * تعديل اسم عنصر في مفتاح الخريطة
     */
    fun updateItemName(toolType: UrbanToolType, newNameAr: String) {
        val currentList = _legendItems.value ?: return
        currentList.find { it.toolType == toolType }?.let {
            it.nameAr = newNameAr
        }
        _legendItems.postValue(currentList)
    }

    /**
     * تبديل ظهور العنصر في مفتاح الخريطة
     */
    fun toggleVisibility(toolType: UrbanToolType) {
        val currentList = _legendItems.value ?: return
        currentList.find { it.toolType == toolType }?.let {
            it.isVisibleInLegend = !it.isVisibleInLegend
        }
        _legendItems.postValue(currentList)
    }

    /**
     * مسح جميع العناصر
     */
    fun clear() {
        _legendItems.value?.clear()
        _legendItems.postValue(mutableListOf())
    }
}
