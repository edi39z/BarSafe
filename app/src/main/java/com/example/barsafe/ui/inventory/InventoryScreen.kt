package com.example.barsafe.ui.inventory

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.barsafe.data.model.InventoryItem
import com.example.barsafe.ui.theme.*
import java.text.NumberFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(viewModel: InventoryViewModel = viewModel()) {
    val items by viewModel.items.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val message by viewModel.message.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<InventoryItem?>(null) }

    var selectionMode by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateListOf<Int>() }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        floatingActionButton = {
            if (selectionMode && selectedItems.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { showDeleteSelectedDialog = true },
                    containerColor = RedDanger,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Delete, "Hapus Terpilih")
                }
            } else if (!selectionMode) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = BlueNeon,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, "Tambah Barang")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(if (selectionMode) "${selectedItems.size} Terpilih" else "Inventaris Aset", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (!selectionMode) {
                        Text("${items.size} barang di dalam brankas", fontSize = 13.sp, color = TextSecondary)
                    }
                }
                if (items.isNotEmpty()) {
                    if (selectionMode) {
                        Row {
                            IconButton(onClick = {
                                if (selectedItems.size == items.size) selectedItems.clear()
                                else { selectedItems.clear(); selectedItems.addAll(items.map { it.id }) }
                            }) { Icon(Icons.Default.SelectAll, null, tint = Color.White) }
                            IconButton(onClick = { selectionMode = false; selectedItems.clear() }) {
                                Icon(Icons.Default.Close, null, tint = Color.White)
                            }
                        }
                    } else {
                        TextButton(onClick = { selectionMode = true }) {
                            Text("Pilih", color = BlueNeon)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (isUploading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    color = BlueNeon, trackColor = DarkSurface
                )
                Spacer(Modifier.height(8.dp))
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Inventory2, null, tint = TextSecondary,
                            modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Belum ada barang tercatat", color = TextSecondary, fontSize = 14.sp)
                        Text("Ketuk + untuk menambahkan", color = TextSecondary.copy(0.6f), fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items, key = { it.id }) { item ->
                        InventoryItemCard(
                            item = item,
                            selectionMode = selectionMode,
                            isSelected = selectedItems.contains(item.id),
                            onSelect = { 
                                if (selectedItems.contains(item.id)) selectedItems.removeAll { it == item.id }
                                else selectedItems.add(item.id)
                            },
                            onEdit = { editItem = it },
                            onDelete = { viewModel.deleteItem(it) }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddEditItemDialog(
            item = null,
            onDismiss = { showAddDialog = false },
            onSave = { name, desc, cat, _, uri ->
                viewModel.addItem(name, desc, cat, 0L, uri)
                showAddDialog = false
            }
        )
    }

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            containerColor = DarkSurface,
            title = { Text("Hapus Barang Terpilih?", color = Color.White) },
            text = { Text("Sebanyak ${selectedItems.size} barang akan dihapus dari inventaris.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { 
                    selectedItems.forEach { id -> 
                        items.find { it.id == id }?.let { viewModel.deleteItem(it) } 
                    }
                    selectedItems.clear()
                    selectionMode = false
                    showDeleteSelectedDialog = false 
                }) {
                    Text("Hapus Semua", color = RedDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("Batal", color = TextSecondary)
                }
            }
        )
    }

    editItem?.let { item ->
        AddEditItemDialog(
            item = item,
            onDismiss = { editItem = null },
            onSave = { name, desc, cat, _, uri ->
                viewModel.updateItem(
                    item.copy(name = name, description = desc, category = cat, estimatedValue = 0L),
                    uri
                )
                editItem = null
            }
        )
    }
}

@Composable
private fun InventoryItemCard(
    item: InventoryItem,
    selectionMode: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: (InventoryItem) -> Unit,
    onDelete: (InventoryItem) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().let { 
            if (selectionMode) it.clickable { onSelect() } else it.clickable { showDetail = true }
        },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) BlueNeon.copy(alpha=0.15f) else DarkSurface),
        elevation = CardDefaults.cardElevation(if (isSelected) 0.dp else 4.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, 
            if (isSelected) BlueNeon.copy(alpha = 0.5f) else Color(0xFF2A2A2A)
        )
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onSelect() }, colors = CheckboxDefaults.colors(checkedColor = BlueNeon))
                Spacer(Modifier.width(8.dp))
            }
            // Image or placeholder
            Box(
                modifier = Modifier.size(60.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A2A2A))
            ) {
                if (item.photoUrl.isNotBlank()) {
                    AsyncImage(
                        model = item.photoUrl,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.ImageNotSupported, null,
                        tint = TextSecondary,
                        modifier = Modifier.size(28.dp).align(Alignment.Center)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(item.description, color = TextSecondary, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryChip(item.category)
                }
            }

            // Actions
            if (!selectionMode) {
                Column {
                    IconButton(onClick = { onEdit(item) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, null, tint = BlueNeon, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, null, tint = RedDanger, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = DarkSurface,
            title = { Text("Hapus Barang?", color = Color.White) },
            text = { Text("\"${item.name}\" akan dihapus dari inventaris.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { onDelete(item); showDeleteConfirm = false }) {
                    Text("Hapus", color = RedDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Batal", color = TextSecondary)
                }
            }
        )
    }

    if (showDetail) {
        InventoryDetailDialog(
            item = item,
            onDismiss = { showDetail = false },
            onDelete = { 
                onDelete(item)
                showDetail = false 
            }
        )
    }
}

@Composable
private fun CategoryChip(category: String) {
    Box(
        modifier = Modifier
            .background(BlueNeon.copy(0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(category, fontSize = 10.sp, color = BlueNeon)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditItemDialog(
    item: InventoryItem?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Long, Uri?) -> Unit
) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var description by remember { mutableStateOf(item?.description ?: "") }
    var category by remember { mutableStateOf(item?.category ?: "Umum") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    val categories = listOf("Umum", "Dokumen", "Perhiasan", "Elektronik", "Uang", "Lainnya")
    var showCatDropdown by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = { Text(if (item == null) "Tambah Barang" else "Edit Barang", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nama Barang *", color = TextSecondary) },
                    colors = outlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Deskripsi", color = TextSecondary) },
                    colors = outlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                // Category selector
                ExposedDropdownMenuBox(
                    expanded = showCatDropdown,
                    onExpandedChange = { showCatDropdown = it }
                ) {
                    OutlinedTextField(
                        value = category, onValueChange = {},
                        readOnly = true,
                        label = { Text("Kategori", color = TextSecondary) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showCatDropdown) },
                        colors = outlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = showCatDropdown,
                        onDismissRequest = { showCatDropdown = false },
                        modifier = Modifier.background(DarkSurface)
                    ) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat, color = Color.White) },
                                onClick = { category = cat; showCatDropdown = false }
                            )
                        }
                    }
                }
                // Photo picker
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { launcher.launch("image/*") },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BlueNeon),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BlueNeon.copy(0.5f))
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pilih Foto", fontSize = 13.sp)
                    }
                    if (selectedUri != null) {
                        Text("✓ Foto dipilih", color = GreenSecure, fontSize = 12.sp)
                    } else if (!item?.photoUrl.isNullOrBlank()) {
                        Text("Ada foto", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name, description, category, 0L, selectedUri)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = BlueNeon)
            ) {
                Text(if (item == null) "Tambah" else "Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BlueNeon,
    unfocusedBorderColor = Color(0xFF3A3A3A),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = BlueNeon
)

@Composable
private fun InventoryDetailDialog(item: InventoryItem, onDismiss: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (item.photoUrl.isNotBlank()) {
                    AsyncImage(
                        model = item.photoUrl,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(260.dp).background(Color(0xFF1E1E1E))
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp).background(BlueNeon.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ImageNotSupported, null, tint = BlueNeon, modifier = Modifier.size(48.dp))
                    }
                }
                
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(item.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    CategoryChip(item.category)
                    Spacer(Modifier.height(12.dp))
                    Text(item.description.ifBlank { "Tidak ada deskripsi." }, color = TextSecondary, fontSize = 15.sp, lineHeight = 22.sp)
                    
                    Spacer(Modifier.height(24.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Tutup", color = TextSecondary) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onDelete,
                            colors = ButtonDefaults.buttonColors(containerColor = RedDanger.copy(alpha = 0.15f), contentColor = RedDanger),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Text("Hapus Barang", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
