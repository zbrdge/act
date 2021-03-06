##########################################################################
#                                                                        #
#  This file is part of the 20n/act project.                             #
#  20n/act enables DNA prediction for synthetic biology/bioengineering.  #
#  Copyright (C) 2017 20n Labs, Inc.                                     #
#                                                                        #
#  Please direct all queries to act@20n.com.                             #
#                                                                        #
#  This program is free software: you can redistribute it and/or modify  #
#  it under the terms of the GNU General Public License as published by  #
#  the Free Software Foundation, either version 3 of the License, or     #
#  (at your option) any later version.                                   #
#                                                                        #
#  This program is distributed in the hope that it will be useful,       #
#  but WITHOUT ANY WARRANTY; without even the implied warranty of        #
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         #
#  GNU General Public License for more details.                          #
#                                                                        #
#  You should have received a copy of the GNU General Public License     #
#  along with this program.  If not, see <http://www.gnu.org/licenses/>. #
#                                                                        #
##########################################################################

# LCMS visualisation configuration-based module

# Module Input function
lcmsConfigTracesInput <- function(id, label = "LCMS config traces") {
  # Create a namespace function using the provided id
  ns <- NS(id)
  tagList(
    h3("Input configuration"),
    fileInput(ns("config.file"), label = "Choose a configuration file"),
    p("Sample config: lcms-demo.json"),
    h3("Peak selection"),
    uiOutput(ns("ui.peaks")),
    em("Peak format is {mz-value} - {retention-time} - {rank-factor} - {molecular-mass (optional)}"),
    em("Flags are appended to the peak description: \"F\" for formulae hits, \"S\" for structure hits"),
    checkboxInput(ns("has.mol.mass"), "Expect multiple m/z values per peak (-M option)", value = FALSE),
    uiOutput(ns("ui.rt.mz.scope")),
    plotParametersInput(ns("plot.parameters")),
    checkboxInput(ns("normalize"), "Normalize values", value = TRUE)
  )
}

# Module UI function
lcmsConfigTracesUI <- function(id) {
  ns <- NS(id)
  fluidPage(
    fluidRow(
      h4("Matching molecules"),
      uiOutput(ns("structures"))
    ),
    fluidRow(
      h4("Matching formulae"),
      uiOutput(ns("formulae"))
    ),
    fluidRow(
      h4("3D scatterplots"),
      uiOutput(ns("plots"))    
    )
  )
}

# Module server function
lcmsConfigTraces <- function(input, output, session) {
  
  ns <- session$ns
  
  # reactive values - re-evaluation is triggered upon input change
  config <- reactive(getAndValidateConfigFile(input$config.file))
  scan.filenames <- reactive(config()$scanfiles$filename)
  layout <- reactive(config()$layout)
  peaks <- reactive(config()$peaks)
  target.mz <- reactive(input$target.mz)
  mz.band.halfwidth <- reactive(input$mz.band.halfwidth)
  normalize <- reactive(input$normalize)
  retention.time.range <- reactive(input$retention.time.range)
  has.mol.mass <- reactive(input$has.mol.mass)
  max.int <- reactive({
    max(unlist(lapply(plot.data(), function(x) max(x$peaks$intensity))))
  })
  
  # render peak selection UI
  output$ui.peaks <- renderUI({
    # round variables and order parsed peaks according to `rank_metric_signif`
    peaks <- peaks() %>% 
      mutate_each(funs(round(.,2)), mz, rt) %>%
      # rank_metric_signif is simply rank_metric with 3 significant digits
      mutate(rank_metric_signif = signif(rank_metric, 3)) %>%
      arrange(desc(rank_metric_signif)) %>%
      # add a flag character column: "F" for formulae matches, "S" for structures matches, "FS" for both, "" for none.
      mutate(flag = paste0(ifelse(matching_formulae != -1, "F", ""), ifelse(matching_inchis != -1, "S", "")))
    # add molecular mass in peak definition if user said so (checkbox)
    if (has.mol.mass()) {
      peaks <- peaks %>%
        mutate_each(funs(round(.,2)), moleculeMass)
      labels <- apply(peaks[, c("mz", "rt", "rank_metric_signif", "moleculeMass", "flag")], 1, function(x) paste0(x, collapse = kPeakDisplaySep))
    } else {
      labels <- apply(peaks[, c("mz", "rt", "rank_metric_signif", "flag")], 1, function(x) paste0(x, collapse = kPeakDisplaySep))
    }
    selectizeInput(ns("peaks"), "Choose a peak to visualize", choices = unname(labels), 
                   # maxOptions is the number of peaks to show in the drop-down menu
                   options = list(maxOptions = 30000))
  })
  
  selected.peak <- reactive({
    shiny::validate(
      need(!is.null(input$peaks), "Waiting for peak selection!")
    )
    splits <- unlist(strsplit(input$peaks, kPeakDisplaySep))
    mz.val <- as.numeric(splits[1])
    rt.val <- as.numeric(splits[2])
    # add molecular mass in peak definition if user said so (checkbox)
    if (has.mol.mass()) {
      mol.mass <- as.numeric(splits[4])
      peak <- peaks() %>% 
        dplyr::filter(round(mz, 2) == mz.val, round(rt, 2) == rt.val, round(moleculeMass, 2) == mol.mass)  
    } else {
      peak <- peaks() %>% 
        dplyr::filter(round(mz, 2) == mz.val, round(rt, 2) == rt.val)
    }
    # check that only one peak was selected for display. Not sure which to display otherwise
    shiny::validate(
      need(nrow(peak) == 1, "Less or more than one peak. Try using the 'expect multiple mz values' checkbox!")
    )
    peak
  })
  
  output$ui.rt.mz.scope <- renderUI({
    selected.peak <- selected.peak()
    rt.min <- selected.peak$rt - selected.peak$rt_band
    rt.max <- selected.peak$rt + selected.peak$rt_band
    tagList(
      sliderInput(ns("retention.time.range"), value = c(rt.min, rt.max), 
                  min = 0, max = 450, label = "Retention time range", step = 1),
      numericInput(ns("target.mz"), label = "Target mz value", value = selected.peak$mz, step = 0.001),
      numericInput(ns("mz.band.halfwidth"), label = "Mass charge band halfwidth", 
                   value = selected.peak$mz_band, step = 0.01)
    )
  })
  
  matching.inchis <- reactive({
    matching.inchis.code <- selected.peak()$matching_inchis
    shiny::validate(
      need(matching.inchis.code != -1, "No matching molecule for this peak!")
    )
    matching.inchi.hashes <- config()$matching_inchi_hashes
    shiny::validate(
      need(length(matching.inchi.hashes) > 0, "Matching molecules have not been computed!")
    )
    codes <- matching.inchi.hashes$code
    named.inchis <- matching.inchi.hashes$vals
    which.code <- which(codes == matching.inchis.code)
    matching.inchis <- named.inchis[[which.code]]
    matching.inchis
  })
  
  matching.formulae <- reactive({
    matching.formulae.code <- selected.peak()$matching_formulae
    shiny::validate(
      need(matching.formulae.code != -1, "No matching formulae for this peak!")
    )
    matching.formulae.hashes <- config()$matching_formulae_hashes
    shiny::validate(
      need(length(matching.formulae.hashes) > 0, "Matching formulae have not been computed!")
    )
    codes <- matching.formulae.hashes$code
    named.formulae <- matching.formulae.hashes$vals
    which.code <- which(codes == matching.formulae.code)
    matching.formulae <- named.formulae[[which.code]]
    matching.formulae
  })
  
  output$structures <- renderUI({
    matching.inchis <- matching.inchis()
    n <- nrow(matching.inchis)
    for (i in 1:n) {
      # we need to call `local` since we don't know when the call will be made
      # `local` evaluates an expression in a local environment
      local({
        my_i <- i
        # At this point, matching.inchis contains a matrix with n rows x 2 columns. 
        # where n is the number of matching inchis, the first column holds the inchi string and the second holds a potential name
        callModule(moleculeRenderer, paste0("plot", my_i), reactive(matching.inchis[my_i, ]), "200px")
      })
    }
    # get a list of rendered molecule
    molecule_output_list <- lapply(1:n, function(i) {
      plotname <- paste0("plot", i)
      chemSpiderUrl <- sprintf("http://www.chemspider.com/Search.aspx?q=%s", matching.inchis[[i]][1])
      # CSS tag `display:inline-block` allows to display all structures on one line
      div(style="display:inline-block", 
          tags$a(moleculeRendererUI(ns(plotname)), href = chemSpiderUrl, target="_blank"))
    })
    # wrap these in a tagList()
    uiStructures <- do.call(tagList, molecule_output_list)
    # wrap in div tag, with some cool CSS tags to fix height and allow overflowing on the x axis
    tagList(
      em("Please scroll horizontally to display all"),
      div(style="height: 200px; overflow-x: auto; white-space: nowrap", uiStructures)  
    )
  })
  
  output$formulae <- renderUI({
    shiny::validate(
      need(dim(matching.formulae()) > 0, "No matching formulae for this peak!")
    )
    # At this point, matching.formulae is a reactive value containing a matrix with n rows x 2 columns. 
    # where n is the number of matching formulae, the first column holds the formula and the second holds a potential formula name
    # The following line concatenates the formula and the name -> "formula - name" into a list of strings.
    named.formulae <- apply(matching.formulae(), 1, function(x) paste(x[!is.na(x)], collapse = " - "))
    formulae <- p(paste(named.formulae, collapse = ", "))
    div(style="height: 50px; overflow-x: auto; white-space: nowrap", formulae) 
  })
  
  plot.data <- callModule(lcmsTracesPeaks, "traces", scan.filenames, retention.time.range, target.mz, mz.band.halfwidth)
  plot.parameters <- callModule(plotParameters, "plot.parameters")

  output$plots <- renderUI({
    
    n <- length(scan.filenames())
    
    # call modules for plot rendering
    for (i in 1:n) {
      # we need to call `local` since we don't know when the call will be made
      # `local` evaluates an expression in a local environment
      local({
        my_i <- i
        callModule(lcmsPlotWithNorm, paste0("plot", my_i), plot.data, plot.parameters, my_i, max.int, normalize)
      })
    }  
    
    layout <- layout()
    # max column width is 12
    colWidth <- 12 / layout$ncol
    plot_output_list <- lapply(1:n, function(i) {
      plotname <- paste0("plot", i)
      column(width = colWidth, lcmsPlotOutput(ns(plotname)))
    })
    # split into a list of indexes for each line
    # example: split(1:5, ceiling(1:5 / 2)) returns a list(c(1,2), c(3,4), 5)
    #          split(1:5, ceiling(1:5 / 3)) returns a list(c(1,2,3), c(4,5))
    plot.indexes <- split(1:n, ceiling(1:n /layout$ncol))
    
    do.call(fluidPage, 
            lapply(1:length(plot.indexes), 
                   function(x) do.call(fluidRow, plot_output_list[plot.indexes[[x]]])))
  })
}
