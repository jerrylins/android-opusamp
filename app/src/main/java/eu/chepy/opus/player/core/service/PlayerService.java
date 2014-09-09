/*
 * PlayerService.java
 *
 * Copyright (c) 2012, Philippe Chepy
 * All rights reserved.
 *
 * This software is the confidential and proprietary information
 * of Philippe Chepy.
 * You shall not disclose such Confidential Information.
 *
 * http://www.chepy.eu
 */
package eu.chepy.opus.player.core.service;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.view.View;

import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import eu.chepy.opus.player.core.NotificationHelper;
import eu.chepy.opus.player.core.RemoteControlClientHelper;
import eu.chepy.opus.player.core.service.providers.AbstractMediaManager;
import eu.chepy.opus.player.ui.utils.PlayerApplication;
import eu.chepy.opus.player.ui.widgets.Widget4x1;
import eu.chepy.opus.player.ui.widgets.Widget4x2;
import eu.chepy.opus.player.utils.LogUtils;


public class PlayerService extends Service implements AbstractMediaManager.Player.OnProviderCompletionListener {

	private final static String TAG = "PlayerService";



    /*
        Command
     */
    public static final String COMMAND_KEY = "command";

    public static final String ACTION_APPWIDGET_COMMAND = "eu.chepy.opus.player.service.APPWIDGET";

    public static final String ACTION_NOTIFICATION_COMMAND = "eu.chepy.opus.player.service.NOTIFICATION";

    public static final String ACTION_TOGGLEPAUSE = "eu.chepy.opus.player.TOGGLE_PAUSE";

    public static final String ACTION_NEXT = "eu.chepy.opus.player.NEXT";

    public static final String ACTION_PREVIOUS = "eu.chepy.opus.player.PREVIOUS";

    public static final String ACTION_STOP = "eu.chepy.opus.player.STOP";



    /*
        Shuffle modes
     */
    public static final int SHUFFLE_NONE = 0;

    public static final int SHUFFLE_AUTO = 1;



    /*
        Repeat modes
     */
    public static final int REPEAT_NONE = 0;

    public static final int REPEAT_CURRENT = 1;

    public static final int REPEAT_ALL = 2;



    /*

     */
    private WakeLock wakelock;

    private ExecutorService uiUpdateExecutor;

    private ExecutorService mediaManagementExecutor;

    private NotificationHelper notificationHelper;

    private RemoteControlClientHelper remoteControlClient;

    private AudioManager audioManager;

    private final PlayerServiceImpl playerServiceImpl = new PlayerServiceImpl();

    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;

    private BroadcastReceiver broadcastReceiver;

    public boolean hasNotification;

    private final Widget4x1 widgetMedium = Widget4x1.getInstance();

    private final Widget4x2 widgetLarge = Widget4x2.getInstance();



    int repeatMode = REPEAT_NONE;

    int shuffleMode = SHUFFLE_NONE;



    private AbstractMediaManager.Media[] playlist = null;

    private ArrayList<Integer> playlistOrder;

    private int playlistOrderIndex;

    private int playlistIndex;

    private Lock notifyMutex = new ReentrantLock();



    /*
     * Current song informations
     */
    private Bitmap currentArt = null;



    @Override
    public void onCodecCompletion() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                LogUtils.LOGD(TAG, "completed in JAVA!");
                try {
                    switch (repeatMode) {
                        case REPEAT_ALL:
                            playerServiceImpl.next();
                            playerServiceImpl.play();
                            break;
                        case REPEAT_CURRENT:
                            playerServiceImpl.setPosition(0);
                            playerServiceImpl.play();
                            break;
                        case REPEAT_NONE:
                            LogUtils.LOGD(TAG, "onCodecCompletion: 2");
                            if (!playerServiceImpl.next()) {
                                LogUtils.LOGD(TAG, "onCodecCompletion: 3");
                                playerServiceImpl.play();
                                LogUtils.LOGD(TAG, "onCodecCompletion: 4");
                            }
                            else {
                                playerServiceImpl.notifyStop(); /* cannot play anymore */
                            }

                            break;
                    }
                }
                catch (final RemoteException remoteException) {
                    LogUtils.LOGException(TAG, "onCodecCompletion", 0, remoteException);
                }
            }
        }).start();
    }

    @Override
    public void onCodecTimestampUpdate(long newPosition) {
        /* Playing, request for timestamp update */
        playerServiceImpl.notifyTimestampUpdate(newPosition);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        uiUpdateExecutor = Executors.newFixedThreadPool(1);
        mediaManagementExecutor = Executors.newFixedThreadPool(1);

        PowerManager powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        notificationHelper = new NotificationHelper(this);
        remoteControlClient = new RemoteControlClientHelper();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        setAudioListener();

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        hasNotification = false;
        repeatMode = sharedPreferences.getInt(PlayerApplication.PREFERENCE_PLAYER_LAST_REPEAT_MODE, REPEAT_NONE);
        shuffleMode = sharedPreferences.getInt(PlayerApplication.PREFERENCE_PLAYER_LAST_SHUFFLE_MODE, SHUFFLE_NONE);
        int position = sharedPreferences.getInt(PlayerApplication.PREFERENCE_PLAYER_LAST_PLAYLIST_POSITION, 0);

        reloadPlaylist();

        if (playlist != null && playlist.length > 0) {
            try {
                playerServiceImpl.queueSetPosition(position);
            } catch (final RemoteException remoteException) {
                LogUtils.LOGException(TAG, "onStartCommand", 0, remoteException);
            }
        }

        try {
            playerServiceImpl.notifyProviderChanged();
        } catch (final RemoteException remoteException) {
            LogUtils.LOGException(TAG, "onStartCommand", 1, remoteException);
        }

        doUpdateWidgets();
        doUpdateRemoteClient();
        doUpdateNotification();

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                doManageCommandIntent(intent);
            }
        };

        registerReceiver(broadcastReceiver, new IntentFilter(ACTION_NOTIFICATION_COMMAND));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            doManageCommandIntent(intent);
        }

        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(broadcastReceiver);

        stopForeground(true);
        hasNotification = false;

        if (uiUpdateExecutor != null) {
            uiUpdateExecutor.shutdown();
        }

        if (mediaManagementExecutor != null) {
            mediaManagementExecutor.shutdown();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return playerServiceImpl;
    }



    class PlayerServiceImpl extends IPlayerService.Stub {

        private RemoteCallbackList<IPlayerServiceListener> playerServiceListeners;

        public PlayerServiceImpl() {
            playerServiceListeners = new RemoteCallbackList<IPlayerServiceListener>();
        }

        @Override
        public void play() throws RemoteException {
            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Player player = mediaManager.getPlayer();

            // should never happen..
            if (!playlist[playlistIndex].isLoaded()) {
                playlist[playlistIndex].load();
                mediaManagementExecutor.submit(loadingManagementRunnable);
            }

            player.playerSetContent(playlist[playlistIndex]); // TODO: move in playlistSetPostion, next, prev.

            uiUpdateExecutor.submit(runnableRefreshSongData);

            if (!player.playerIsPlaying()) {
                player.playerPlay();
                wakelock.acquire();
                notifyPlay();
            }
        }

        @Override
        public void pause(boolean keepNotification) throws RemoteException {
            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Player player = mediaManager.getPlayer();

            if (player.playerIsPlaying()) {
                player.playerPause(true);
                notifyPause(keepNotification);
                wakelock.release();
            }

            notifyPause(true);
        }

        @Override
        public void stop() throws RemoteException {
            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Player player = mediaManager.getPlayer();

            player.playerStop();

            notifyStop();
            if (wakelock.isHeld()) {
                wakelock.release();
            }
        }

        @Override
        public boolean next() throws RemoteException {
            boolean looped = false;

            if (playlist != null && playlist.length > 1) {
                looped = doMoveToNextPosition();
                notifySetQueuePosition();
            }

            return looped;
        }

        @Override
        public boolean prev() throws RemoteException {
            boolean looped = false;

            if (playlist != null && playlist.length > 1) {
                looped = doMoveToPrevPosition();
                notifySetQueuePosition();
            }

            return looped;
        }

        @Override
        public boolean isPlaying() throws RemoteException {
            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Player player = mediaManager.getPlayer();

            return player.playerIsPlaying();
        }

        @Override
        public long getDuration() throws RemoteException {
            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Player player = mediaManager.getPlayer();

            return player.playerGetDuration();
        }

        @Override
        public long getPosition() throws RemoteException {
            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Player player = mediaManager.getPlayer();

            return player.playerGetPosition();
        }

        @Override
        public void setPosition(long position) throws RemoteException {
            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Player player = mediaManager.getPlayer();

            player.playerSeek(position);
            notifyTimestampUpdate(position);
        }

        @Override
        public int getShuffleMode() throws RemoteException {
            return shuffleMode;
        }

        @Override
        public void setShuffleMode(int mode) throws RemoteException {
            shuffleMode = mode;
            if (shuffleMode == SHUFFLE_AUTO) {
                if (getRepeatMode() == REPEAT_CURRENT) {
                    setRepeatMode(REPEAT_ALL);
                }
            }

            notifyShuffleChange();
        }

        @Override
        public int getRepeatMode() throws RemoteException {
            return repeatMode;
        }

        @Override
        public void setRepeatMode(int mode) throws RemoteException {
            repeatMode = mode;
            if (repeatMode == REPEAT_CURRENT) {
                if (getShuffleMode() != SHUFFLE_NONE) {
                    setShuffleMode(SHUFFLE_NONE);
                }
            }

            notifyRepeatChange();
        }

        @Override
        public void queueAdd(String media) throws RemoteException {
            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Provider provider = mediaManager.getProvider();
            provider.playlistAdd(null, AbstractMediaManager.Provider.ContentType.CONTENT_TYPE_MEDIA, media, 0, null);

            reloadPlaylist();

            if (playlistIndex >= playlist.length) {
                playlistIndex = playlist.length - 1;
            }

            if (playlist.length == 1) {
                play();
            }

            notifyQueueChanged();
        }

        @Override
        public void queueMove(int indexFrom, int indexTo) throws RemoteException {
            if (indexFrom == indexTo) {
                return;
            }

            if (indexFrom < playlistIndex && indexTo >= playlistIndex) {
                playlistIndex--;
            }
            else if (indexFrom > playlistIndex && indexTo <= playlistIndex) {
                playlistIndex++;
            }
            else if (indexFrom == playlistIndex) {
                playlistIndex = indexTo;
            }

            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Provider provider = mediaManager.getProvider();
            provider.playlistMove(null, indexFrom, indexTo);

            reloadPlaylist();

            if (playlistIndex >= playlist.length) {
                playlistIndex = playlist.length - 1;
            }
        }

        @Override
        public void queueRemove(int entry) throws RemoteException {
            if (entry < playlistIndex) {
                playlistIndex--;
            }

            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Provider provider = mediaManager.getProvider();
            provider.playlistRemove(null, entry);

            boolean wasPlaying = isPlaying();

            if (playlistIndex == entry) {
                wasPlaying = false;
                stop();
            }
            else {
                pause(true);
            }

            reloadPlaylist();

            if (playlist.length != 0) {
                if (playlistIndex <= playlist.length) {
                    playlistIndex = playlist.length - 1;
                }

                if (wasPlaying) {
                    play();
                }
            }

            notifySetQueuePosition();
            notifyQueueChanged();
        }

        @Override
        public void queueClear() throws RemoteException {
            if (isPlaying()) {
                stop();
            }

            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Provider provider = mediaManager.getProvider();

            provider.playlistClear(null);
            reloadPlaylist();

            notifyQueueChanged();
        }

        @Override
        public void queueReload() throws RemoteException {
            boolean wasPlaying = isPlaying();

            if (wasPlaying) {
                pause(true);
            }

            String uri = null;
            if (playlist.length > playlistIndex) {
                uri = playlist[playlistIndex].getUri();
            }

            reloadPlaylist();

            if (playlistIndex >= playlist.length) {
                playlistIndex = 0;
            }

            if (playlist.length > playlistIndex) {
                if (playlist[playlistIndex].getUri().equals(uri)) {
                    if (wasPlaying) {
                        play();
                    }
                }
            }

            notifyQueueChanged();
        }


        @Override
        public void queueSetPosition(int position) throws RemoteException {

            if (playlist != null && playlist.length > 0) {
                LogUtils.LOGD(TAG, "setQueuePosition() : moving to position " + position);

                if (shuffleMode == SHUFFLE_NONE) {
                    if (playlistIndex != position) {
                        playlistIndex = position;
                        if (playlistIndex >= playlist.length) {
                            playlistIndex = 0;
                        }
                    }
                } else if (shuffleMode == SHUFFLE_AUTO) {
                    int currentTrackIndex = playlistOrder.get(playlistOrderIndex);
                    if (currentTrackIndex != position) {
                        int indexOfPosition = playlistOrder.indexOf(position);
                        playlistOrder.set(indexOfPosition, currentTrackIndex);
                        playlistOrder.set(playlistOrderIndex, position);

                        playlistIndex = position;
                    }
                }

                notifySetQueuePosition();
            }
        }

        @Override
        public int queueGetPosition() throws RemoteException {
            if (playlist == null || playlist.length == 0) {
                return -1;
            }

            if (shuffleMode == SHUFFLE_NONE) {
                return playlistIndex;
            }
            else if (shuffleMode == SHUFFLE_AUTO) {
                return playlistOrder.get(playlistOrderIndex);
            }

            return 0;
        }

        @Override
        public int queueGetSize() throws RemoteException {
            return playlist.length;
        }

        @Override
        public void registerPlayerCallback(IPlayerServiceListener playerServiceListener) throws RemoteException {
            if (!playerServiceListeners.register(playerServiceListener)) {
                throw new RemoteException();
            }
        }

        @Override
        public void unregisterPlayerCallback(IPlayerServiceListener playerServiceListener) throws RemoteException {
            if (!playerServiceListeners.unregister(playerServiceListener)) {
                throw new RemoteException();
            }
        }

        @Override
        public void notifyProviderChanged() throws RemoteException {
            lockNotify();
            final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
            final AbstractMediaManager.Player player = mediaManager.getPlayer();

            player.addCompletionListener(PlayerService.this);
            unlockNotify();
        }

        public void notifyTimestampUpdate(long timestamp) {
            lockNotify();
            int count = playerServiceListeners.beginBroadcast();
            try {
                for (int index = 0 ; index < count ; index++) {
                    playerServiceListeners.getBroadcastItem(index).onSeek(timestamp);
                }
            }
            catch (final RemoteException exception) {
                LogUtils.LOGException(TAG, "notifyTimestampUpdate", 0, exception);
            }
            playerServiceListeners.finishBroadcast();
            unlockNotify();
        }

        public void notifyPlay() {
            uiUpdateExecutor.submit(runnablePlay);

            lockNotify();
            /*
                Client ui update
             */
            int count = playerServiceListeners.beginBroadcast();
            try {
                for (int index = 0 ; index < count ; index++) {
                    playerServiceListeners.getBroadcastItem(index).onPlay();
                }
            }
            catch (final RemoteException exception) {
                LogUtils.LOGException(TAG, "notifyPlay", 0, exception);
            }
            playerServiceListeners.finishBroadcast();
            unlockNotify();
        }

        public void notifyPause(boolean keepNotification) {
            runnablePause.keepNotification = keepNotification;
            uiUpdateExecutor.submit(runnablePause);

            lockNotify();
            /*
                Client ui update
             */
            int count = playerServiceListeners.beginBroadcast();
            try {
                for (int index = 0 ; index < count ; index++) {
                    playerServiceListeners.getBroadcastItem(index).onPause();
                }
            }
            catch (final RemoteException exception) {
                LogUtils.LOGException(TAG, "notifyPause", 0, exception);
            }
            playerServiceListeners.finishBroadcast();
            unlockNotify();
        }

        public void notifyStop() {
            uiUpdateExecutor.submit(runnableStop);

            lockNotify();
            /*
                Client ui update
             */
            int count = playerServiceListeners.beginBroadcast();
            try {
                for (int index = 0 ; index < count ; index++) {
                    playerServiceListeners.getBroadcastItem(index).onStop();
                }
            }
            catch (final RemoteException exception) {
                LogUtils.LOGException(TAG, "notifyStop", 0, exception);
            }
            playerServiceListeners.finishBroadcast();
            unlockNotify();
        }

        private void notifyQueueChanged() {
            doUpdateWidgets();

            lockNotify();
            final int count = playerServiceListeners.beginBroadcast();
            for (int index = 0 ; index < count; index++) {
                try {
                    playerServiceListeners.getBroadcastItem(index).onQueueChanged();
                }
                catch (final RemoteException remoteException) {
                    LogUtils.LOGException(TAG, "notifyQueueChanged", 0, remoteException);
                }
            }
            playerServiceListeners.finishBroadcast();

            final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            final SharedPreferences.Editor edit = sharedPreferences.edit();
            edit.putInt(PlayerApplication.PREFERENCE_PLAYER_LAST_PLAYLIST_POSITION, playlistIndex);
            edit.apply();
            unlockNotify();
        }

        private void notifyShuffleChange() {
            lockNotify();
            final int count = playerServiceListeners.beginBroadcast();
            for (int index = 0 ; index < count; index++) {
                try {
                    playerServiceListeners.getBroadcastItem(index).onShuffleModeChanged();
                }
                catch (final RemoteException remoteException) {
                    LogUtils.LOGException(TAG, "notifyShuffleChange", 0, remoteException);
                }
            }
            playerServiceListeners.finishBroadcast();

            final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            final SharedPreferences.Editor edit = sharedPreferences.edit();
            edit.putInt(PlayerApplication.PREFERENCE_PLAYER_LAST_SHUFFLE_MODE, shuffleMode);
            edit.apply();
            unlockNotify();
        }

        private void notifyRepeatChange() {
            lockNotify();
            final int count = playerServiceListeners.beginBroadcast();
            for (int index = 0 ; index < count; index++) {
                try {
                    playerServiceListeners.getBroadcastItem(index).onRepeatModeChanged();
                }
                catch (final RemoteException remoteException) {
                    LogUtils.LOGException(TAG, "notifyRepeatChange", 0, remoteException);
                }
            }
            playerServiceListeners.finishBroadcast();

            final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            final SharedPreferences.Editor edit = sharedPreferences.edit();
            edit.putInt(PlayerApplication.PREFERENCE_PLAYER_LAST_REPEAT_MODE, repeatMode);
            edit.apply();
            unlockNotify();
        }

        private void notifySetQueuePosition() {
            mediaManagementExecutor.submit(loadingManagementRunnable);

            lockNotify();
            final int count = playerServiceListeners.beginBroadcast();
            for (int index = 0 ; index < count; index++) {
                try {
                    playerServiceListeners.getBroadcastItem(index).onQueuePositionChanged();
                }
                catch (final RemoteException remoteException) {
                    LogUtils.LOGException(TAG, "notifySetQueuePosition", 0, remoteException);
                }
            }
            playerServiceListeners.finishBroadcast();

            final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            final SharedPreferences.Editor edit = sharedPreferences.edit();
            edit.putInt(PlayerApplication.PREFERENCE_PLAYER_LAST_PLAYLIST_POSITION, playlistIndex);
            edit.apply();
            unlockNotify();
        }
    }

    @TargetApi(14)
    private void setAudioListener() {
        if (PlayerApplication.hasICS()) {
            audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        remoteControlClient.release();
                        try {
                            playerServiceImpl.pause(false);
                        } catch (final Exception exception) {
                            LogUtils.LOGException(TAG, "setAudioListener", 0, exception);
                        }
                    }
                }
            };
        }
    }

    protected void doUpdateWidgets() {
        try {
            boolean isPlaying = playerServiceImpl.isPlaying();

            final AbstractMediaManager.Media media = playlist[playlistIndex];

            widgetLarge.notifyChange(PlayerService.this, media.name, media.artist, media.album, currentArt, isPlaying);
            widgetMedium.notifyChange(PlayerService.this, media.name, media.artist, media.album, currentArt, isPlaying);
        }
        catch (final Exception exception) {
            LogUtils.LOGException(TAG, "doUpdateWidgets", 0, exception);
        }
    }

    protected void doUpdateRemoteClient() {
        final AbstractMediaManager.Media media = playlist[playlistIndex];
        remoteControlClient.updateMetadata(currentArt, media.name, media.artist, media.album, media.duration);
    }

    protected void doUpdateNotification() {
        final AbstractMediaManager.Media media = playlist[playlistIndex];
        notificationHelper.buildNotification(media.album, media.artist, media.name, currentArt);
    }

    protected void doManageCommandIntent(final Intent intent) {
        if (intent.hasExtra(COMMAND_KEY)) {
            final String source = intent.getAction();
            final String action = intent.getStringExtra(PlayerService.COMMAND_KEY);

            boolean isNotificationControl = source.equals(PlayerService.ACTION_NOTIFICATION_COMMAND);
            boolean isWidgetControl = source.equals(PlayerService.ACTION_APPWIDGET_COMMAND);
            boolean isRemoteControl = isNotificationControl || isWidgetControl;

            if (action != null && isRemoteControl) {
                try {
                    if (action.equals(PlayerService.ACTION_PREVIOUS)) {
                        if (playerServiceImpl.isPlaying()) {
                            playerServiceImpl.pause(true);
                            playerServiceImpl.prev();
                            playerServiceImpl.play();
                        }
                        else {
                            playerServiceImpl.prev();
                        }
                    } else if (action.equals(PlayerService.ACTION_NEXT)) {
                        if (playerServiceImpl.isPlaying()) {
                            playerServiceImpl.pause(true);
                            playerServiceImpl.next();
                            playerServiceImpl.play();
                        }
                        else {
                            playerServiceImpl.next();
                        }
                    } else if (action.equals(PlayerService.ACTION_STOP)) {
                        playerServiceImpl.stop();
                    } else if (action.equals(PlayerService.ACTION_TOGGLEPAUSE)) {
                        if (playerServiceImpl.isPlaying()) {
                            playerServiceImpl.pause(isNotificationControl);
                        } else {
                            playerServiceImpl.play();
                        }
                    }
                }
                catch (final RemoteException remoteException) {
                    LogUtils.LOGException(TAG, "doManageCommandIntent", 0, remoteException);
                }
            }
        }
    }

    protected boolean doMoveToNextPosition() {
        boolean looped = false;
        if (shuffleMode == SHUFFLE_NONE) {
            playlistIndex++;
            if (playlistIndex >= playlist.length) {
                playlistIndex = 0;
                looped = true;
            }
        }
        else if (shuffleMode == SHUFFLE_AUTO) {
            playlistOrderIndex++;
            if (playlistOrderIndex >= playlistOrder.size()) {
                playlistOrderIndex = 0;
                looped = true;
            }


            playlistIndex = playlistOrder.get(playlistOrderIndex);
        }
        return looped;
    }

    protected boolean doMoveToPrevPosition() {
        boolean looped = false;
        if (shuffleMode == SHUFFLE_NONE) {
            playlistIndex--;
            if (playlistIndex < 0) {
                playlistIndex = playlist.length - 1;
                looped = true;
            }
        }
        else if (shuffleMode == SHUFFLE_AUTO) {
            playlistOrderIndex--;
            if (playlistOrderIndex < 0) {
                playlistOrderIndex = playlistOrder.size() - 1;
                looped = true;
            }

            playlistIndex = playlistOrder.get(playlistOrderIndex);
        }
        return looped;
    }

    protected void reloadPlaylist() {

        final AbstractMediaManager mediaManager = PlayerApplication.mediaManagers[PlayerApplication.playerManagerIndex];
        final AbstractMediaManager.Provider provider = mediaManager.getProvider();

        playlist = provider.getCurrentPlaylist(mediaManager.getPlayer());

        int playlistLength = playlist != null ? playlist.length : 0;

        playlistOrder = new ArrayList<Integer>();
        for (int playlistIndex = 0 ; playlistIndex < playlistLength ; playlistIndex++) {
            playlistOrder.add(playlistIndex);
        }

        Collections.shuffle(playlistOrder);
        playlistOrderIndex = 0;
    }

    protected void lockNotify() {
        notifyMutex.lock();
    }

    protected void unlockNotify() {
        notifyMutex.unlock();
    }


    private ImageLoadingListener artImageLoaderListener = new ImageLoadingListener() {

        @Override
        public void onLoadingStarted(String imageUri, View view) {
        }

        @Override
        public void onLoadingFailed(String imageUri, View view, FailReason failReason) {
            currentArt = null;
            doUpdateNotification();
            doUpdateRemoteClient();
            doUpdateWidgets();
        }

        @Override
        public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
            String currentArtUri = null;
            if (playlistIndex < playlist.length) {
                currentArtUri = playlist[playlistIndex].artUri;
            }

            if (imageUri.equals(currentArtUri)) {
                if (loadedImage != null && loadedImage.isRecycled()) {
                    PlayerApplication.normalImageLoader.loadImage(currentArtUri, (DisplayImageOptions) null, artImageLoaderListener);
                }
                else {
                    currentArt = loadedImage;
                    doUpdateNotification();
                    doUpdateRemoteClient();
                    doUpdateWidgets();
                }
            }
        }

        @Override
        public void onLoadingCancelled(String imageUri, View view) {
            currentArt = null;
            doUpdateNotification();
            doUpdateRemoteClient();
            doUpdateWidgets();
        }
    };

    private Runnable runnablePlay = new Runnable() {

        @Override
        public void run() {
            /*
                System ui update
             */
            int audioFocus = AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

            if (PlayerApplication.hasICS()) {
                audioFocus = audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }

            remoteControlClient.register(PlayerService.this, audioManager);
            if (audioFocus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                if (hasNotification) {
                    notificationHelper.goToIdleState(true);
                } else {
                    startForeground(PlayerApplication.NOTIFICATION_PLAY_ID, notificationHelper.getNotification());
                    hasNotification = true;
                }
                remoteControlClient.updateState(true);
            }

            doUpdateWidgets();
            doUpdateRemoteClient();
            doUpdateNotification();
        }
    };

    private RunnablePause runnablePause = new RunnablePause();

    class RunnablePause implements Runnable {

        public boolean keepNotification = false;

        @Override
        public void run() {
            /*
                System ui update
             */
            remoteControlClient.updateState(false);

            if (keepNotification) {
                notificationHelper.goToIdleState(false);
            }
            else {
                stopForeground(true);
                hasNotification = false;

                if (PlayerApplication.hasICS()) {
                    audioManager.abandonAudioFocus(audioFocusChangeListener);
                }
            }

            doUpdateWidgets();
        }
    }

    private Runnable runnableStop = new Runnable() {

        @Override
        public void run() {
            /*
                System ui update
             */
            stopForeground(true);
            hasNotification = false;
            remoteControlClient.stop();
            remoteControlClient.release();

            doUpdateWidgets();

            if (PlayerApplication.hasICS()) {
                audioManager.abandonAudioFocus(audioFocusChangeListener);
            }
        }
    };

    // TODO: fix, showing previous' song bitmap...
    private Runnable runnableRefreshSongData = new Runnable() {
        @Override
        public void run() {
            if (playlist != null) {
                String currentArtUri = null;
                if (playlistIndex < playlist.length) {
                    currentArtUri = playlist[playlistIndex].artUri;
                }

                PlayerApplication.normalImageLoader.loadImage(currentArtUri, (DisplayImageOptions) null, artImageLoaderListener);
            }
            else {
                currentArt = null;
            }

            doUpdateNotification();
            doUpdateRemoteClient();
            doUpdateWidgets();
        }
    };

    class UnloadRunnable implements Runnable {

        public AbstractMediaManager.Media track;

        public UnloadRunnable(AbstractMediaManager.Media track) {
            this.track = track;
        }

        @Override
        public void run() {
            track.unload();
        }
    }

    class LoadRunnable implements Runnable {

        public AbstractMediaManager.Media track;

        public LoadRunnable(AbstractMediaManager.Media track) {
            this.track = track;
        }

        @Override
        public void run() {
            track.load();
        }
    }

    private LoadingManagementRunnable loadingManagementRunnable = new LoadingManagementRunnable();

    class LoadingManagementRunnable implements Runnable {
        @Override
        public void run() {
            try {
                Thread.sleep(2000); // that's a hack :( preload
                // TODO: enable preloading at
                //      get int delay = AbstractMediaProvider.preloadingPreferedDelay();
                //      if (delay > 0) {
                //          Thread.sleep(delay);
                //      }
                //      else {
                //          Thread.sleep(currentSong.duration - currentTimestamp - delay);
                //      }
            }
            catch (final Exception interruptException) {

            }

            int nextPlaylistIndex = playlistIndex;

            if (shuffleMode == SHUFFLE_NONE) {
                nextPlaylistIndex++;
                if (nextPlaylistIndex >= playlist.length) {
                    nextPlaylistIndex = 0;
                }
            }
            else if (shuffleMode == SHUFFLE_AUTO) {
                int nextPlaylistOrderIndex = playlistOrderIndex + 1;
                if (nextPlaylistOrderIndex >= playlistOrder.size()) {
                    nextPlaylistOrderIndex = 0;
                }

                nextPlaylistIndex = playlistOrder.get(nextPlaylistOrderIndex);
            }

            int prevPlaylistIndex = playlistIndex;
            if (shuffleMode == SHUFFLE_NONE) {
                prevPlaylistIndex--;
                if (prevPlaylistIndex < 0) {
                    prevPlaylistIndex = playlist.length - 1;
                }
            }
            else if (shuffleMode == SHUFFLE_AUTO) {
                int prevPlaylistOrderIndex = playlistOrderIndex - 1;
                if (prevPlaylistOrderIndex < 0) {
                    prevPlaylistOrderIndex = playlistOrder.size() - 1;
                }

                prevPlaylistIndex = playlistOrder.get(prevPlaylistOrderIndex);
            }

            // TODO: memory leak.. unload all minus current and next, then load next.
            mediaManagementExecutor.submit(new UnloadRunnable(playlist[prevPlaylistIndex]));
            mediaManagementExecutor.submit(new LoadRunnable(playlist[nextPlaylistIndex]));
        }
    }
}
