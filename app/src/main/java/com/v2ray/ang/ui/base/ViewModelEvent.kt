package com.v2ray.ang.ui.base

interface ViewModelEvent

interface BaseViewModelEvent : ViewModelEvent {
    object FinishActivity : BaseViewModelEvent
}
