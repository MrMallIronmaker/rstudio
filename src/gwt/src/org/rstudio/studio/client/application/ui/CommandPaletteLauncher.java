/*
 * CommandPaletteLauncher.java
 *
 * Copyright (C) 2020 by RStudio, PBC
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.application.ui;

import java.util.ArrayList;
import java.util.List;

import org.rstudio.core.client.Debug;
import org.rstudio.core.client.command.CommandBinder;
import org.rstudio.core.client.command.Handler;
import org.rstudio.core.client.command.ShortcutManager;
import org.rstudio.core.client.widget.ModalPopupPanel;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.workbench.addins.AddinsCommandManager;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.prefs.model.PrefsServerOperations;
import org.rstudio.studio.client.workbench.prefs.model.UserPrefDefinitions;
import org.rstudio.studio.client.workbench.views.source.Source;

import com.google.gwt.aria.client.Roles;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

@Singleton
public class CommandPaletteLauncher implements CommandPalette.Host
{
   public interface Binder
          extends CommandBinder<Commands, CommandPaletteLauncher> {}
   
   private enum State
   {
      Uninitialized,  // The panel has never been shown
      Initializing,   // The panel is getting ready to show for the first time
      Showing,        // The panel is currently showing
      Hidden          // The panel is ready, but currently hidden
   }
   
   @Inject 
   public CommandPaletteLauncher(Commands commands,
         PrefsServerOperations prefsServer,
         AddinsCommandManager addins,
         Provider<Source> pSource,
         Binder binder)
   {
      binder.bind(commands, this);
      addins_ = addins;
      commands_ = commands;
      pSource_ = pSource;
      prefsServer_ = prefsServer;
      state_ = State.Uninitialized;
   }
   
   @Handler
   public void onShowCommandPalette()
   {
      // If the palette is already showing, treat this as a hide.
      if (state_ == State.Showing)
      {
         dismiss();
         return;
      }

      if (state_ == State.Hidden)
      {
         // Already initialized; just show the panel
         createPanel();
      }
      else if (state_ == State.Uninitialized)
      {
         // Not yet initialized. The first load happens behind the browser event
         // loop (and after an RPC) so that the CSS resources can be injected.
         // We could fix this by eagerly injecting these when RStudio starts,
         // but this way we don't pay any boot time penalty.
         state_ = State.Initializing;
         prefsServer_.getAllPreferences(new ServerRequestCallback<UserPrefDefinitions>()
         {
            @Override
            public void onResponseReceived(UserPrefDefinitions defs)
            {
               // Save the preference definitions and create the UI
               prefs_ = defs;
               state_ = State.Hidden;
               createPanel();
            }
            
            @Override
            public void onError(ServerError error)
            {
               // Log the error to the console for diagnostics
               Debug.logError(error);
               
               // Create an empty set of preferences. This means that user
               // preferences won't show in the Command Palette, but that
               // shouldn't stop us for using the palette for other tasks.
               prefs_ = UserPrefDefinitions.createEmpty();
               state_ = State.Hidden;
               createPanel();
            }
         });
      }
   }
   
   /**
    * Creates the popup panel that hosts the palette. Since this panel is
    * relatively heavyweight (it can hold a large number of commands), we create
    * it anew each time the palette is shown.
    */
   private void createPanel()
   {
      // extra sources (currently only the source tab)
      List<CommandPaletteEntrySource> extraSources = new ArrayList<CommandPaletteEntrySource>();
      extraSources.add(pSource_.get());
      
      // Create the command palette widget
      palette_ = new CommandPalette(commands_, addins_.getRAddins(), prefs_, extraSources,
            ShortcutManager.INSTANCE, this);
      
      panel_ = new ModalPopupPanel(
            true,  // Auto hide
            true,  // Modal
            false, // Glass (main window overlay)
            true   // Close on Esc
      );
      
      // Copy classes from the root RStudio container onto this panel. This is
      // necessary so that we can properly inherit theme colors.
      Element root = Document.get().getElementById("rstudio_container");
      if (root != null)
      {
         panel_.addStyleName(root.getClassName());
      }
      
      // Assign the appropriate ARIA role to this panel
      Element ele = panel_.getElement();
      Roles.getDialogRole().set(ele);
      Roles.getDialogRole().setAriaLabelProperty(ele, "Search commands and settings");

      panel_.add(palette_);
      panel_.show();
      panel_.center();
      
      // Set z-index above panel splitters (otherwise they overlap the popup)
      panel_.getElement().getStyle().setZIndex(250);

      palette_.focus();
      state_ = State.Showing;
      
      // Free our reference to the panel when it closes
      panel_.addCloseHandler((evt) -> 
      {
         cleanup();
      });
   }

   @Override
   public void dismiss()
   {
      panel_.hide();
      cleanup();
   }
   
   /**
    * Free references to the palette and panel 
    */
   private void cleanup()
   {
      palette_ = null;
      panel_ = null;
      state_ = State.Hidden;
   }
   
   private ModalPopupPanel panel_;
   private CommandPalette palette_;
   private State state_;
   private UserPrefDefinitions prefs_;

   private final PrefsServerOperations prefsServer_;
   private final Commands commands_;
   private final AddinsCommandManager addins_;
   private final Provider<Source> pSource_;
}
