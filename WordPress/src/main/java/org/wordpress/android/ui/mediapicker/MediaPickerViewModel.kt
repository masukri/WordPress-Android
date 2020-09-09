package org.wordpress.android.ui.mediapicker

import android.Manifest.permission
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wordpress.android.R
import org.wordpress.android.analytics.AnalyticsTracker
import org.wordpress.android.analytics.AnalyticsTracker.Stat.MEDIA_PICKER_OPEN_WP_STORIES_CAPTURE
import org.wordpress.android.analytics.AnalyticsTracker.Stat.MEDIA_PICKER_PREVIEW_OPENED
import org.wordpress.android.analytics.AnalyticsTracker.Stat.MEDIA_PICKER_RECENT_MEDIA_SELECTED
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.modules.BG_THREAD
import org.wordpress.android.modules.UI_THREAD
import org.wordpress.android.ui.mediapicker.MediaItem.Identifier
import org.wordpress.android.ui.mediapicker.MediaItem.Identifier.RemoteId
import org.wordpress.android.ui.mediapicker.MediaItem.Identifier.LocalUri
import org.wordpress.android.ui.mediapicker.MediaLoader.DomainModel
import org.wordpress.android.ui.mediapicker.MediaLoader.LoadAction
import org.wordpress.android.ui.mediapicker.MediaLoader.LoadAction.NextPage
import org.wordpress.android.ui.mediapicker.MediaPickerFragment.MediaPickerIcon
import org.wordpress.android.ui.mediapicker.MediaPickerFragment.MediaPickerIcon.WP_STORIES_CAPTURE
import org.wordpress.android.ui.mediapicker.MediaPickerUiItem.ClickAction
import org.wordpress.android.ui.mediapicker.MediaPickerUiItem.FileItem
import org.wordpress.android.ui.mediapicker.MediaPickerUiItem.PhotoItem
import org.wordpress.android.ui.mediapicker.MediaPickerUiItem.ToggleAction
import org.wordpress.android.ui.mediapicker.MediaPickerUiItem.VideoItem
import org.wordpress.android.ui.mediapicker.MediaType.AUDIO
import org.wordpress.android.ui.mediapicker.MediaType.DOCUMENT
import org.wordpress.android.ui.mediapicker.MediaType.IMAGE
import org.wordpress.android.ui.mediapicker.MediaType.VIDEO
import org.wordpress.android.ui.photopicker.PermissionsHandler
import org.wordpress.android.ui.utils.UiString
import org.wordpress.android.ui.utils.UiString.UiStringRes
import org.wordpress.android.ui.utils.UiString.UiStringText
import org.wordpress.android.util.LocaleManagerWrapper
import org.wordpress.android.util.MediaUtils
import org.wordpress.android.util.MediaUtilsWrapper
import org.wordpress.android.util.UriWrapper
import org.wordpress.android.util.ViewWrapper
import org.wordpress.android.util.WPPermissionUtils
import org.wordpress.android.util.analytics.AnalyticsTrackerWrapper
import org.wordpress.android.util.analytics.AnalyticsUtilsWrapper
import org.wordpress.android.util.distinct
import org.wordpress.android.util.merge
import org.wordpress.android.viewmodel.Event
import org.wordpress.android.viewmodel.ResourceProvider
import org.wordpress.android.viewmodel.ScopedViewModel
import javax.inject.Inject
import javax.inject.Named

class MediaPickerViewModel @Inject constructor(
    @Named(UI_THREAD) private val mainDispatcher: CoroutineDispatcher,
    @Named(BG_THREAD) private val bgDispatcher: CoroutineDispatcher,
    private val mediaLoaderFactory: MediaLoaderFactory,
    private val analyticsUtilsWrapper: AnalyticsUtilsWrapper,
    private val analyticsTrackerWrapper: AnalyticsTrackerWrapper,
    private val permissionsHandler: PermissionsHandler,
    private val context: Context,
    private val localeManagerWrapper: LocaleManagerWrapper,
    private val mediaUtilsWrapper: MediaUtilsWrapper,
    private val resourceProvider: ResourceProvider
) : ScopedViewModel(mainDispatcher) {
    private lateinit var mediaLoader: MediaLoader
    private val loadActions = Channel<LoadAction>()
    private val _navigateToPreview = MutableLiveData<Event<UriWrapper>>()
    private val _navigateToEdit = MutableLiveData<Event<List<UriWrapper>>>()
    private val _onInsert = MutableLiveData<Event<List<Identifier>>>()
    private val _showPopupMenu = MutableLiveData<Event<PopupMenuUiModel>>()
    private val _domainModel = MutableLiveData<DomainModel>()
    private val _selectedIds = MutableLiveData<List<Identifier>>()
    private val _onIconClicked = MutableLiveData<Event<IconClickEvent>>()
    private val _onPermissionsRequested = MutableLiveData<Event<PermissionsRequested>>()
    private val _softAskRequest = MutableLiveData<SoftAskRequest>()
    private val _searchExpanded = MutableLiveData<Boolean>()

    val onNavigateToPreview: LiveData<Event<UriWrapper>> = _navigateToPreview
    val onNavigateToEdit: LiveData<Event<List<UriWrapper>>> = _navigateToEdit
    val onInsert: LiveData<Event<List<Identifier>>> = _onInsert
    val onIconClicked: LiveData<Event<IconClickEvent>> = _onIconClicked

    val onShowPopupMenu: LiveData<Event<PopupMenuUiModel>> = _showPopupMenu
    val onPermissionsRequested: LiveData<Event<PermissionsRequested>> = _onPermissionsRequested

    val uiState: LiveData<MediaPickerUiState> = merge(
            _domainModel.distinct(),
            _selectedIds.distinct(),
            _softAskRequest,
            _searchExpanded
    ) { domainModel, selectedIds, softAskRequest, searchExpanded ->
        MediaPickerUiState(
                buildUiModel(domainModel, selectedIds),
                buildSoftAskView(softAskRequest),
                FabUiModel(mediaPickerSetup.cameraEnabled) {
                    clickIcon(WP_STORIES_CAPTURE)
                },
                buildActionModeUiModel(selectedIds, domainModel?.domainItems),
                buildSearchUiModel(domainModel?.filter, searchExpanded),
                isRefreshing = !domainModel?.domainItems.isNullOrEmpty() && domainModel?.isLoading == true
        )
    }

    private fun buildSearchUiModel(filter: String?, searchExpanded: Boolean?): SearchUiModel {
        return if (searchExpanded == true) {
            SearchUiModel.Expanded(filter ?: "")
        } else {
            SearchUiModel.Collapsed
        }
    }

    var lastTappedIcon: MediaPickerIcon? = null
    private lateinit var mediaPickerSetup: MediaPickerSetup
    private var site: SiteModel? = null

    private fun buildUiModel(
        domainModel: DomainModel?,
        selectedIds: List<Identifier>?
    ): PhotoListUiModel {
        val data = domainModel?.domainItems
        return if (data != null) {
            val uiItems = data.map {
                val showOrderCounter = mediaPickerSetup.canMultiselect
                val toggleAction = ToggleAction(it.identifier, showOrderCounter, this::toggleItem)
                val clickAction = ClickAction(it.identifier, it.type == VIDEO, this::clickItem)
                val (selectedOrder, isSelected) = if (selectedIds != null && selectedIds.contains(it.identifier)) {
                    val selectedOrder = if (showOrderCounter) selectedIds.indexOf(it.identifier) + 1 else null
                    val isSelected = true
                    selectedOrder to isSelected
                } else {
                    null to false
                }

                val fileExtension = it.mimeType?.let { mimeType ->
                    mediaUtilsWrapper.getExtensionForMimeType(mimeType).toUpperCase(localeManagerWrapper.getLocale())
                }
                when (it.type) {
                    IMAGE -> PhotoItem(
                            url = it.url,
                            identifier = it.identifier,
                            isSelected = isSelected,
                            selectedOrder = selectedOrder,
                            showOrderCounter = showOrderCounter,
                            toggleAction = toggleAction,
                            clickAction = clickAction
                    )
                    VIDEO -> VideoItem(
                            url = it.url,
                            identifier = it.identifier,
                            isSelected = isSelected,
                            selectedOrder = selectedOrder,
                            showOrderCounter = showOrderCounter,
                            toggleAction = toggleAction,
                            clickAction = clickAction
                    )
                    AUDIO, DOCUMENT -> FileItem(
                            fileName = it.name ?: "",
                            fileExtension = fileExtension,
                            identifier = it.identifier,
                            isSelected = isSelected,
                            selectedOrder = selectedOrder,
                            showOrderCounter = showOrderCounter,
                            toggleAction = toggleAction,
                            clickAction = clickAction
                    )
                }
            }
            if (domainModel.hasMore) {
                val updatedItems = uiItems.toMutableList()
                updatedItems.add(MediaPickerUiItem.NextPageLoader(true, domainModel.error) {
                    launch {
                        loadActions.send(NextPage)
                    }
                })
                PhotoListUiModel.Data(updatedItems)
            } else {
                PhotoListUiModel.Data(uiItems)
            }
        } else {
            PhotoListUiModel.Empty
        }
    }

    private fun buildActionModeUiModel(
        selectedIds: List<Identifier>?,
        items: List<MediaItem>?
    ): ActionModeUiModel {
        val numSelected = selectedIds?.size ?: 0
        if (selectedIds.isNullOrEmpty()) {
            return ActionModeUiModel.Hidden
        }
        val title: UiString? = when {
            numSelected == 0 -> null
            mediaPickerSetup.canMultiselect -> {
                UiStringText(String.format(resourceProvider.getString(R.string.cab_selected), numSelected))
            }
            else -> {
                val isImagePicker = mediaPickerSetup.allowedTypes.contains(IMAGE)
                val isVideoPicker = mediaPickerSetup.allowedTypes.contains(VIDEO)
                if (isImagePicker && isVideoPicker) {
                    UiStringRes(R.string.photo_picker_use_media)
                } else if (isVideoPicker) {
                    UiStringRes(R.string.photo_picker_use_video)
                } else {
                    UiStringRes(R.string.photo_picker_use_photo)
                }
            }
        }
        val onlyImagesSelected = items?.any { it.type != IMAGE && selectedIds.contains(it.identifier) } ?: false
        return ActionModeUiModel.Visible(
                title,
                showEditAction = mediaPickerSetup.allowedTypes.contains(IMAGE) && !onlyImagesSelected
        )
    }

    fun refreshData(forceReload: Boolean) {
        if (!permissionsHandler.hasStoragePermission()) {
            return
        }
        launch(bgDispatcher) {
            loadActions.send(LoadAction.Refresh)
        }
    }

    fun clearSelection() {
        if (!_selectedIds.value.isNullOrEmpty()) {
            _selectedIds.postValue(listOf())
        }
    }

    fun start(
        selectedUris: List<UriWrapper>?,
        selectedIds: List<Long>?,
        mediaPickerSetup: MediaPickerSetup,
        lastTappedIcon: MediaPickerIcon?,
        site: SiteModel?
    ) {
        _selectedIds.value = selectedUris?.map { LocalUri(it) } ?: selectedIds?.map { RemoteId(it) }
        this.mediaPickerSetup = mediaPickerSetup
        this.lastTappedIcon = lastTappedIcon
        this.site = site
        if (_domainModel.value == null) {
            this.mediaLoader = mediaLoaderFactory.build(mediaPickerSetup, site)
            launch(bgDispatcher) {
                mediaLoader.loadMedia(loadActions).collect { domainModel ->
                    withContext(mainDispatcher) {
                        _domainModel.value = domainModel
                    }
                }
            }
            launch(bgDispatcher) {
                loadActions.send(LoadAction.Start())
            }
        }
    }

    fun numSelected(): Int {
        return _selectedIds.value?.size ?: 0
    }

    fun selectedURIs(): List<UriWrapper> {
        return selectedIdentifiers().mapNotNull { (it as? LocalUri)?.value }
    }

    fun selectedIds(): List<Long> {
        return selectedIdentifiers().mapNotNull { (it as? RemoteId)?.value }
    }

    private fun selectedIdentifiers(): List<Identifier> {
        return _selectedIds.value ?: listOf()
    }

    private fun toggleItem(identifier: Identifier, canMultiselect: Boolean) {
        val updatedUris = _selectedIds.value?.toMutableList() ?: mutableListOf()
        if (updatedUris.contains(identifier)) {
            updatedUris.remove(identifier)
        } else {
            if (updatedUris.isNotEmpty() && !canMultiselect) {
                updatedUris.clear()
            }
            updatedUris.add(identifier)
        }
        _selectedIds.postValue(updatedUris)
    }

    private fun clickItem(identifier: Identifier, isVideo: Boolean) {
        trackOpenPreviewScreenEvent(identifier, isVideo)
        if (identifier is LocalUri) {
            _navigateToPreview.postValue(Event(identifier.value))
        }
    }

    private fun trackOpenPreviewScreenEvent(identifier: Identifier, isVideo: Boolean) {
        launch(bgDispatcher) {
            if (identifier is LocalUri) {
                val properties = analyticsUtilsWrapper.getMediaProperties(
                        isVideo,
                        identifier.value,
                        null
                )
                properties["is_video"] = isVideo
                analyticsTrackerWrapper.track(MEDIA_PICKER_PREVIEW_OPENED, properties)
            } else {
                TODO()
            }
        }
    }

    fun performInsertAction() {
        val ids = selectedIdentifiers()
        _onInsert.value = Event(ids)
        val isMultiselection = ids.size > 1
        for (identifier in ids) {
            if (identifier is LocalUri) {
                val isVideo = MediaUtils.isVideo(identifier.toString())
                val properties = analyticsUtilsWrapper.getMediaProperties(
                        isVideo,
                        identifier.value,
                        null
                )
                properties["is_part_of_multiselection"] = isMultiselection
                if (isMultiselection) {
                    properties["number_of_media_selected"] = ids.size
                }
                analyticsTrackerWrapper.track(MEDIA_PICKER_RECENT_MEDIA_SELECTED, properties)
            }
        }
    }

    fun performEditAction() {
        val uriList = selectedURIs()
        _navigateToEdit.value = Event(uriList)
    }

    fun clickOnLastTappedIcon() = clickIcon(lastTappedIcon!!)

    private fun clickIcon(icon: MediaPickerIcon) {
        if (icon == WP_STORIES_CAPTURE) {
            if (!permissionsHandler.hasPermissionsToAccessPhotos()) {
                _onPermissionsRequested.value = Event(PermissionsRequested.CAMERA)
                lastTappedIcon = icon
                return
            }
            AnalyticsTracker.track(MEDIA_PICKER_OPEN_WP_STORIES_CAPTURE)
        }
        _onIconClicked.postValue(Event(IconClickEvent(icon, mediaPickerSetup.canMultiselect)))
    }

    fun checkStoragePermission(isAlwaysDenied: Boolean) {
        if (permissionsHandler.hasStoragePermission()) {
            _softAskRequest.value = SoftAskRequest(show = false, isAlwaysDenied = isAlwaysDenied)
            if (_domainModel.value?.domainItems.isNullOrEmpty()) {
                refreshData(false)
            }
        } else {
            _softAskRequest.value = SoftAskRequest(show = true, isAlwaysDenied = isAlwaysDenied)
        }
    }

    private fun buildSoftAskView(softAskRequest: SoftAskRequest?): SoftAskViewUiModel {
        if (softAskRequest != null && softAskRequest.show) {
            val appName = "<strong>${resourceProvider.getString(R.string.app_name)}</strong>"
            val label = if (softAskRequest.isAlwaysDenied) {
                val permissionName = ("<strong>${
                    WPPermissionUtils.getPermissionName(
                            context,
                            permission.WRITE_EXTERNAL_STORAGE
                    )
                }</strong>")
                String.format(
                        resourceProvider.getString(R.string.photo_picker_soft_ask_permissions_denied), appName,
                        permissionName
                )
            } else {
                String.format(
                        resourceProvider.getString(R.string.photo_picker_soft_ask_label),
                        appName
                )
            }
            val allowId = if (softAskRequest.isAlwaysDenied) {
                R.string.button_edit_permissions
            } else {
                R.string.photo_picker_soft_ask_allow
            }
            return SoftAskViewUiModel.Visible(label, UiStringRes(allowId), softAskRequest.isAlwaysDenied)
        } else {
            return SoftAskViewUiModel.Hidden
        }
    }

    fun onSearch(query: String) {
        launch(bgDispatcher) {
            loadActions.send(LoadAction.Filter(query))
        }
    }

    fun onSearchExpanded() {
        _searchExpanded.value = true
    }

    fun onSearchCollapsed() {
        _searchExpanded.value = false
        launch(bgDispatcher) {
            loadActions.send(LoadAction.ClearFilter)
        }
    }

    fun onPullToRefresh() {
        launch {
            loadActions.send(LoadAction.Refresh)
        }
    }

    data class MediaPickerUiState(
        val photoListUiModel: PhotoListUiModel,
        val softAskViewUiModel: SoftAskViewUiModel,
        val fabUiModel: FabUiModel,
        val actionModeUiModel: ActionModeUiModel,
        val searchUiModel: SearchUiModel,
        val isRefreshing: Boolean
    )

    sealed class PhotoListUiModel {
        data class Data(val items: List<MediaPickerUiItem>) :
                PhotoListUiModel()

        object Empty : PhotoListUiModel()
    }

    sealed class SoftAskViewUiModel {
        data class Visible(val label: String, val allowId: UiStringRes, val isAlwaysDenied: Boolean) :
                SoftAskViewUiModel()

        object Hidden : SoftAskViewUiModel()
    }

    data class FabUiModel(val show: Boolean, val action: () -> Unit)

    sealed class ActionModeUiModel {
        data class Visible(
            val actionModeTitle: UiString? = null,
            val showEditAction: Boolean = false
        ) : ActionModeUiModel()

        object Hidden : ActionModeUiModel()
    }

    sealed class SearchUiModel {
        object Collapsed : SearchUiModel()
        data class Expanded(val filter: String) : SearchUiModel()
    }

    data class IconClickEvent(val icon: MediaPickerIcon, val allowMultipleSelection: Boolean)

    enum class PermissionsRequested {
        CAMERA, STORAGE
    }

    data class PopupMenuUiModel(val view: ViewWrapper, val items: List<PopupMenuItem>) {
        data class PopupMenuItem(val title: UiStringRes, val action: () -> Unit)
    }

    data class SoftAskRequest(val show: Boolean, val isAlwaysDenied: Boolean)
}