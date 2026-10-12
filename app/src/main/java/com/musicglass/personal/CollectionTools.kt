package com.musicglass.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

internal enum class CollectionSort(private val labelRes:Int) {
    Original(R.string.manual_ordering),Title(R.string.title),TitleDescending(R.string.title_z_a),Artist(R.string.artist),Newest(R.string.newest_first),Oldest(R.string.oldest_first),Album(R.string.album),TopVoted(R.string.top_voted);
    val label:String get()=string(labelRes)
}
private fun searchKey(text:String)=Normalizer.normalize(text,Normalizer.Form.NFKD).replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT)
/** Indexes continue to refer to the full authored collection, even after searching and sorting. */
internal fun <T> collectionView(rows:List<T>,query:String,sort:CollectionSort,title:(T)->String,artist:(T)->String,added:(T)->Long?={null},album:(T)->String={""}):List<IndexedValue<T>> {
    val terms=searchKey(query).split(Regex("\\s+")).filter {it.isNotBlank()}
    val filtered=rows.withIndex().filter {row->val text=searchKey(title(row.value)+" "+artist(row.value));terms.all(text::contains)}
    val collator=Collator.getInstance().apply {strength=Collator.PRIMARY}
    val titleOrder=Comparator<IndexedValue<T>> {a,b->collator.compare(title(a.value),title(b.value))}
    return when(sort) {
        CollectionSort.Original,CollectionSort.TopVoted->filtered
        CollectionSort.Title->filtered.sortedWith(titleOrder)
        CollectionSort.TitleDescending->filtered.sortedWith(titleOrder.reversed())
        CollectionSort.Artist->filtered.sortedWith(Comparator<IndexedValue<T>> {a,b->collator.compare(artist(a.value),artist(b.value))}.then(titleOrder))
        CollectionSort.Album->filtered.sortedWith(Comparator<IndexedValue<T>> {a,b->
            val left=album(a.value);val right=album(b.value)
            if(left.isBlank()!=right.isBlank()) if(left.isBlank()) 1 else -1 else collator.compare(left,right)
        }.then(titleOrder))
        CollectionSort.Newest->filtered.sortedWith(compareByDescending<IndexedValue<T>> {added(it.value)?:Long.MIN_VALUE})
        CollectionSort.Oldest->filtered.sortedWith(compareBy<IndexedValue<T>> {added(it.value)?:Long.MAX_VALUE})
    }
}
@Composable internal fun CollectionTools(label:String,query:String,onQuery:(String)->Unit,sort:CollectionSort,onSort:(CollectionSort)->Unit,hasDates:Boolean,playlist:Boolean=false,serverSorts:Set<CollectionSort> = emptySet(),busy:Boolean=false) {
    var expanded by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query,onQuery,singleLine=true,shape=RoundedCornerShape(24.dp),label={Text(string(R.string.search_x, label),fontSize=13.sp)},
            textStyle=LocalTextStyle.current.copy(fontSize=14.sp,color=White),leadingIcon={Icon(Icons.Rounded.Search,null,tint=White.copy(alpha=.65f))},
            trailingIcon={if(query.isNotEmpty()) IconButton({onQuery("")}) {Icon(Icons.Rounded.Close,string(R.string.clear_search),tint=White)}},
            modifier=Modifier.fillMaxWidth().testTag("collection_search"))
        Box {
            FilterChip(true,{uiHaptic();expanded=true},label={Text(sort.label,fontSize=12.sp)},leadingIcon={Icon(Icons.Rounded.Sort,null,modifier=Modifier.size(16.dp))},modifier=Modifier.testTag("collection_sort"))
            SongActionMenu(expanded,{expanded=false}) {
                val options=if(playlist) listOf(CollectionSort.TopVoted,CollectionSort.Original,CollectionSort.Newest,CollectionSort.Oldest,CollectionSort.Title,CollectionSort.Artist,CollectionSort.Album)
                    else CollectionSort.entries.filterNot {it in setOf(CollectionSort.TopVoted,CollectionSort.Album)}
                options.forEach {option->
                    val supplied=option in serverSorts || when(option) {
                        CollectionSort.Newest,CollectionSort.Oldest->hasDates
                        CollectionSort.TopVoted->false
                        else->true
                    }
                    val enabled=supplied && !busy
                    DropdownMenuItem(text={Column {
                        Text(option.label,color=if(enabled) White else Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(!supplied) Text(if(option==CollectionSort.TopVoted) string(R.string.voting_not_available_for_this_playlist) else string(R.string.dates_not_supplied_for_this_collection),fontSize=11.sp,color=Muted)
                    }},enabled=enabled,onClick={uiHaptic(HapticCue.Selection);onSort(option);expanded=false},trailingIcon={if(sort==option) Icon(Icons.Rounded.Check,null,tint=Pink)},modifier=Modifier.testTag("collection_sort_${option.name.lowercase()}"))
                }
            }
        }
    }
}
