package com.cookiegames.smartcookie.html.bookmark

import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.constant.FILE
import com.cookiegames.smartcookie.database.Bookmark
import com.cookiegames.smartcookie.database.bookmark.BookmarkRepository
import com.cookiegames.smartcookie.di.DatabaseScheduler
import com.cookiegames.smartcookie.di.DiskScheduler
import com.cookiegames.smartcookie.extensions.safeUse
import com.cookiegames.smartcookie.favicon.FaviconModel
import com.cookiegames.smartcookie.favicon.toValidUri
import com.cookiegames.smartcookie.html.HtmlPageFactory
import com.cookiegames.smartcookie.html.jsoup.*
import com.cookiegames.smartcookie.utils.ThemeUtils
import android.app.Application
import android.graphics.Bitmap
import androidx.core.net.toUri
import dagger.Reusable
import io.reactivex.Scheduler
import io.reactivex.Single
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import javax.inject.Inject

/**
 * Created by anthonycr on 9/23/18.
 */
@Reusable
class BookmarkPageFactory @Inject constructor(
        private val application: Application,
        private val bookmarkModel: BookmarkRepository,
        private val faviconModel: FaviconModel,
        @DatabaseScheduler private val databaseScheduler: Scheduler,
        @DiskScheduler private val diskScheduler: Scheduler,
        private val bookmarkPageReader: BookmarkPageReader
) : HtmlPageFactory {

    private val title = application.getString(R.string.action_bookmarks)
    private val folderIconFile by lazy { File(application.cacheDir, FOLDER_ICON) }
    private val defaultIconFile by lazy { File(application.cacheDir, DEFAULT_ICON) }

    override fun buildPage(): Single<String> = bookmarkModel
            .getAllBookmarksSorted()
            .flattenAsObservable { it }
            .groupBy<Bookmark.Folder, Bookmark>(Bookmark.Entry::folder) { it }
            .flatMapSingle { bookmarksInFolder ->
                val folder = bookmarksInFolder.key
                return@flatMapSingle bookmarksInFolder
                        .toList()
                        .concatWith(
                                if (folder == Bookmark.Folder.Root) {
                                    bookmarkModel.getFoldersSorted().map { it.filterIsInstance<Bookmark.Folder.Entry>() }
                                } else {
                                    Single.just(emptyList())
                                }
                        )
                        .toList()
                        .map { bookmarksAndFolders ->
                            Pair(folder, bookmarksAndFolders.flatten().map { it.asViewModel() })
                        }
            }
            .map { (folder, viewModels) -> Pair(folder, construct(viewModels)) }
            .subscribeOn(databaseScheduler)
            .observeOn(diskScheduler)
            .doOnNext { (folder, content) ->
                FileWriter(createBookmarkPage(folder), false).use {
                    it.write(content)
                }
            }
            .ignoreElements()
            .toSingle {
                cacheIcon(ThemeUtils.createThemedBitmap(application, R.drawable.ic_folder, false), folderIconFile)
                cacheIcon(faviconModel.createDefaultBitmapForTitle(null), defaultIconFile)

                "$FILE${createBookmarkPage(null)}"
            }

    private fun cacheIcon(icon: Bitmap, file: File) = FileOutputStream(file).safeUse {
        icon.compress(Bitmap.CompressFormat.PNG, 100, it)
        icon.recycle()
    }

    private fun construct(list: List<BookmarkViewModel>): String {
        return parse(bookmarkPageReader.provideHtml(application)) andBuild {
            title { title }
            body {
                val repeatableElement = id("repeated").removeElement()
                id("content") {
                    list.forEach {
                        appendChild(repeatableElement.clone {
                            tag("a") { attr("href", it.url) }
                            tag("img") { attr("src", it.iconUrl) }
                            id("title") { appendText(it.title) }
                        })
                    }
                }
            }
        }
    }

    private fun Bookmark.asViewModel(): BookmarkViewModel = when (this) {
        is Bookmark.Folder -> createViewModelForFolder(this)
        is Bookmark.Entry -> createViewModelForBookmark(this)
    }

    private fun createViewModelForFolder(folder: Bookmark.Folder): BookmarkViewModel {
        val folderPage = createBookmarkPage(folder)
        val url = "$FILE$folderPage"

        return BookmarkViewModel(
                title = folder.title,
                url = url,
                iconUrl = folderIconFile.toString()
        )
    }

    private fun createViewModelForBookmark(entry: Bookmark.Entry): BookmarkViewModel {
        val bookmarkUri = entry.url.toUri().toValidUri()

        val iconUrl = if (bookmarkUri != null) {
            val faviconFile = FaviconModel.getFaviconCacheFile(application, bookmarkUri)
            if (!faviconFile.exists()) {
                val defaultFavicon = faviconModel.createDefaultBitmapForTitle(entry.title)
                faviconModel.cacheFaviconForUrl(defaultFavicon, entry.url)
                        .subscribeOn(diskScheduler)
                        .subscribe()
            }

            faviconFile
        } else {
            defaultIconFile
        }

        if(bookmarkUri == null){
            return BookmarkViewModel(
                    title = "entry.title",
                    url = "entry.url",
                    iconUrl = "iconUrl.toString()"
            )
        }

        return BookmarkViewModel(
                title = entry.title,
                url = entry.url,
                iconUrl = iconUrl.toString()
        )
    }

    /**
     * Create the bookmark page file.
     */
    fun createBookmarkPage(folder: Bookmark.Folder?): File {
        val prefix = if (folder?.title?.isNotBlank() == true) {
            "${folder.title}-"
        } else {
            ""
        }
        return File(application.filesDir, prefix + FILENAME)
    }

    companion object {

        const val FILENAME = "bookmark.html"

        private const val FOLDER_ICON = "folder.png"
        private const val DEFAULT_ICON = "default.png"

    }
}