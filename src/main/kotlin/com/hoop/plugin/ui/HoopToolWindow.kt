package com.hoop.plugin.ui

import com.hoop.plugin.model.HoopConnection
import com.hoop.plugin.service.HoopService
import com.hoop.plugin.settings.HoopSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

class HoopToolWindow(private val project: Project) {

    private val hoopService = HoopService.getInstance(project)
    private val settings = HoopSettings.getInstance(project)
    private val connections = mutableListOf<HoopConnection>()
    private lateinit var table: JBTable
    private lateinit var tableModel: ConnectionTableModel
    private val outputArea = JTextArea(10, 50).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    fun getContent(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 10))
        mainPanel.border = JBUI.Borders.empty(10)

        // Painel superior com ações principais organizadas em grid
        val topPanel = createModernTopPanel()
        mainPanel.add(topPanel, BorderLayout.NORTH)

        // Painel central com tabela e botão de conectar
        val centerPanel = createCenterPanel()
        mainPanel.add(centerPanel, BorderLayout.CENTER)

        // Área de output (log)
        val outputPanel = createOutputPanel()
        mainPanel.add(outputPanel, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun createModernTopPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(0, 0, 10, 0)

        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(5)
        gbc.weightx = 1.0

        // Primeira linha - Login e Listar
        gbc.gridx = 0
        gbc.gridy = 0
        val loginButton = JButton("Login no Hoop").apply {
            toolTipText = "Abre o navegador para autenticação no Hoop"
            addActionListener { performLogin() }
        }
        panel.add(loginButton, gbc)

        gbc.gridx = 1
        val listButton = JButton("Listar Conexões").apply {
            toolTipText = "Carrega todas as conexões disponíveis"
            addActionListener { loadConnections() }
        }
        panel.add(listButton, gbc)

        // Segunda linha - Verificar Status e Desconectar
        gbc.gridx = 0
        gbc.gridy = 1
        val statusButton = JButton("Verificar Status").apply {
            toolTipText = "Mostra quais conexões estão ativas"
            addActionListener { checkStatus() }
        }
        panel.add(statusButton, gbc)

        gbc.gridx = 1
        val killButton = JButton("Desconectar Tudo").apply {
            toolTipText = "Encerra todas as conexões ativas"
            addActionListener { killAll() }
        }
        panel.add(killButton, gbc)

        return panel
    }

    private fun createCenterPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 10))

        // Tabela de conexões
        tableModel = ConnectionTableModel()
        table = JBTable(tableModel)
        table.setShowGrid(true)
        table.rowHeight = 25

        // Configurar larguras das colunas (sem coluna Status)
        table.columnModel.getColumn(0).apply {
            preferredWidth = 50
            maxWidth = 50
        }
        table.columnModel.getColumn(1).preferredWidth = 400
        table.columnModel.getColumn(2).apply {
            preferredWidth = 100
            maxWidth = 150
        }

        val scrollPane = JBScrollPane(table)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Painel inferior da tabela com botão de conectar
        val tableFooter = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 10))
        tableFooter.border = JBUI.Borders.empty(5, 0, 0, 0)

        val connectButton = JButton("Conectar Selecionados").apply {
            toolTipText = "Inicia conexão com os bancos selecionados"
            preferredSize = Dimension(180, 30)
            addActionListener { connectSelected() }
        }
        tableFooter.add(connectButton)

        panel.add(tableFooter, BorderLayout.SOUTH)

        return panel
    }

    private fun createOutputPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 5))
        panel.border = JBUI.Borders.empty(10, 0, 0, 0)

        val label = JLabel("Log de Conexões:")
        label.border = JBUI.Borders.empty(0, 0, 5, 0)
        panel.add(label, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(outputArea)
        scrollPane.preferredSize = Dimension(0, 120)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun performLogin() {
        outputArea.append("Iniciando login... (abrirá o browser)\n")
        ApplicationManager.getApplication().executeOnPooledThread {
            hoopService.login { success, message ->
                SwingUtilities.invokeLater {
                    outputArea.append("$message\n")
                    // Removido o popup de alerta - apenas mostra no log
                }
            }
        }
    }

    private fun loadConnections() {
        outputArea.append("Carregando conexões...\n")
        ApplicationManager.getApplication().executeOnPooledThread {
            val loadedConnections = hoopService.listConnections()

            SwingUtilities.invokeLater {
                connections.clear()
                connections.addAll(loadedConnections)

                // Restaurar APENAS as portas salvas (não marcar como selecionado)
                connections.forEach { conn ->
                    settings.savedConnections[conn.name]?.let { savedPort ->
                        conn.port = savedPort
                    }
                }

                tableModel.fireTableDataChanged()
                outputArea.append("${connections.size} conexões encontradas\n")
            }
        }
    }

    private fun connectSelected() {
        val selected = connections.filter { it.selected }
        if (selected.isEmpty()) {
            Messages.showWarningDialog(project, "Selecione ao menos uma conexão", "Aviso")
            return
        }

        // Salvar configurações de porta
        selected.forEach { conn ->
            settings.savedConnections[conn.name] = conn.port
        }

        outputArea.append("\n=== Iniciando ${selected.size} conexão(ões) ===\n")

        selected.forEach { conn ->
            outputArea.append("→ Conectando ${conn.name} na porta ${conn.port}\n")

            ApplicationManager.getApplication().executeOnPooledThread {
                hoopService.connectToDatabase(
                    connection = conn,
                    onOutput = { output ->
                        SwingUtilities.invokeLater {
                            outputArea.append("[${conn.name}:${conn.port}] $output")
                        }
                    },
                    onDisconnect = { connectionName, exitCode ->
                        // Callback opcional para quando a conexão é encerrada
                        // Pode ser usado para atualizar UI ou outras ações
                    }
                )
            }
        }
    }

    private fun checkStatus() {
        outputArea.append("\n=== Verificando conexões ativas ===\n")
        ApplicationManager.getApplication().executeOnPooledThread {
            val active = hoopService.getActiveConnections()

            SwingUtilities.invokeLater {
                if (active.isEmpty()) {
                    outputArea.append("✗ Nenhuma conexão ativa\n")
                } else {
                    outputArea.append("✓ Conexões ativas (${active.size}):\n")
                    active.forEach { outputArea.append("  • $it\n") }
                }
            }
        }
    }

    private fun killAll() {
        outputArea.append("\n=== Encerrando todas as conexões ===\n")
        ApplicationManager.getApplication().executeOnPooledThread {
            hoopService.killAllConnections { success, message ->
                SwingUtilities.invokeLater {
                    outputArea.append("$message\n")
                }
            }
        }
    }

    inner class ConnectionTableModel : AbstractTableModel() {
        private val columnNames = arrayOf("", "Nome da Conexão", "Porta")

        override fun getRowCount(): Int = connections.size

        override fun getColumnCount(): Int = columnNames.size

        override fun getColumnName(column: Int): String = columnNames[column]

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                0 -> java.lang.Boolean::class.java
                2 -> Integer::class.java
                else -> String::class.java
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            return columnIndex == 0 || columnIndex == 2 // Checkbox e Porta editáveis
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val conn = connections[rowIndex]
            return when (columnIndex) {
                0 -> conn.selected
                1 -> conn.name
                2 -> if (conn.port == 0) "" else conn.port
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val conn = connections[rowIndex]
            when (columnIndex) {
                0 -> conn.selected = aValue as Boolean
                2 -> {
                    val value = aValue.toString().toIntOrNull() ?: 0
                    if (value > 0) {
                        conn.port = value
                    }
                }
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }
}