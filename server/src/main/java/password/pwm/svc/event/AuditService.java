/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2023 The PWM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package password.pwm.svc.event;

import org.apache.commons.csv.CSVPrinter;
import password.pwm.AppProperty;
import password.pwm.PwmApplication;
import password.pwm.PwmApplicationMode;
import password.pwm.PwmConstants;
import password.pwm.bean.EmailItemBean;
import password.pwm.bean.SessionLabel;
import password.pwm.config.Configuration;
import password.pwm.config.PwmSetting;
import password.pwm.config.option.DataStorageMethod;
import password.pwm.config.option.UserEventStorageMethod;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmException;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.health.HealthMessage;
import password.pwm.health.HealthRecord;
import password.pwm.http.PwmSession;
import password.pwm.i18n.Display;
import password.pwm.ldap.UserInfo;
import password.pwm.svc.PwmService;
import password.pwm.svc.stats.Statistic;
import password.pwm.svc.stats.StatisticsManager;
import password.pwm.util.i18n.LocaleHelper;
import password.pwm.util.java.JavaHelper;
import password.pwm.util.java.JsonUtil;
import password.pwm.util.java.StringUtil;
import password.pwm.util.java.TimeDuration;
import password.pwm.util.localdb.LocalDB;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.macro.MacroRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AuditService implements PwmService
{
    private static final PwmLogger LOGGER = PwmLogger.forClass( AuditService.class );

    private STATUS status = STATUS.CLOSED;
    private AuditSettings settings;
    private ServiceInfoBean serviceInfo = ServiceInfoBean.builder().build();

    private SyslogAuditService syslogManager;
    private ErrorInformation lastError;
    private UserHistoryStore userHistoryStore;
    private AuditVault auditVault;

    private PwmApplication pwmApplication;

    public AuditService( )
    {
    }

    @Override
    public STATUS status( )
    {
        return status;
    }

    @Override
    public void init( final PwmApplication pwmApplication ) throws PwmException
    {
        this.pwmApplication = pwmApplication;

        final Instant startTime = Instant.now();

        settings = AuditSettings.fromConfig( pwmApplication.getConfig() );

        if ( pwmApplication.getApplicationMode() == null || pwmApplication.getApplicationMode() == PwmApplicationMode.READ_ONLY )
        {
            this.status = STATUS.CLOSED;
            LOGGER.warn( () -> "unable to start - Application is in read-only mode" );
            return;
        }

        if ( pwmApplication.getLocalDB() == null || pwmApplication.getLocalDB().status() != LocalDB.Status.OPEN )
        {
            this.status = STATUS.CLOSED;
            LOGGER.warn( () -> "unable to start - LocalDB is not available" );
            return;
        }

        final List<String> syslogConfigString = pwmApplication.getConfig().readSettingAsStringArray( PwmSetting.AUDIT_SYSLOG_SERVERS );
        if ( syslogConfigString != null && !syslogConfigString.isEmpty() )
        {
            try
            {
                syslogManager = new SyslogAuditService( pwmApplication );
            }
            catch ( final Exception e )
            {
                final ErrorInformation errorInformation = new ErrorInformation( PwmError.ERROR_SYSLOG_WRITE_ERROR, "startup error: " + e.getMessage() );
                LOGGER.error( errorInformation::toDebugStr );
            }
        }
        {
            final UserEventStorageMethod userEventStorageMethod = pwmApplication.getConfig().readSettingAsEnum(
                    PwmSetting.EVENTS_USER_STORAGE_METHOD,
                    UserEventStorageMethod.class
            );
            final String debugMsg;
            final DataStorageMethod storageMethodUsed;
            switch ( userEventStorageMethod )
            {
                case AUTO:
                    if ( pwmApplication.getConfig().hasDbConfigured() )
                    {
                        debugMsg = "starting using auto-configured data store, Remote Database selected";
                        this.userHistoryStore = new DatabaseUserHistory( pwmApplication );
                        storageMethodUsed = DataStorageMethod.DB;
                    }
                    else
                    {
                        debugMsg = "starting using auto-configured data store, LDAP selected";
                        this.userHistoryStore = new LdapXmlUserHistory( pwmApplication );
                        storageMethodUsed = DataStorageMethod.LDAP;
                    }
                    break;

                case DATABASE:
                    this.userHistoryStore = new DatabaseUserHistory( pwmApplication );
                    debugMsg = "starting using Remote Database data store";
                    storageMethodUsed = DataStorageMethod.DB;
                    break;

                case LDAP:
                    this.userHistoryStore = new LdapXmlUserHistory( pwmApplication );
                    debugMsg = "starting using LocalDB data store";
                    storageMethodUsed = DataStorageMethod.LDAP;
                    break;

                default:
                    lastError = new ErrorInformation( PwmError.ERROR_INTERNAL, "unknown storageMethod selected: " + userEventStorageMethod );
                    status = STATUS.CLOSED;
                    return;
            }
            LOGGER.debug( () -> debugMsg, () -> TimeDuration.fromCurrent( startTime ) );
            serviceInfo = ServiceInfoBean.builder().storageMethod( storageMethodUsed ).build();
        }
        {
            final TimeDuration maxRecordAge = TimeDuration.of( pwmApplication.getConfig().readSettingAsLong( PwmSetting.EVENTS_AUDIT_MAX_AGE ), TimeDuration.Unit.SECONDS );
            final long maxRecords = pwmApplication.getConfig().readSettingAsLong( PwmSetting.EVENTS_AUDIT_MAX_EVENTS );
            final AuditVault.Settings settings = new AuditVault.Settings(
                    maxRecords,
                    maxRecordAge
            );

            if ( pwmApplication.getLocalDB() != null && pwmApplication.getApplicationMode() != PwmApplicationMode.READ_ONLY )
            {
                if ( maxRecords < 1 )
                {
                    LOGGER.debug( () -> "localDB audit vault will remain closed due to max records setting" );
                    pwmApplication.getLocalDB().truncate( LocalDB.DB.AUDIT_EVENTS );
                }
                else
                {
                    auditVault = new LocalDbAuditVault();
                    auditVault.init( pwmApplication, pwmApplication.getLocalDB(), settings );
                }
            }
            else
            {
                LOGGER.debug( () -> "localDB audit vault will remain closed due to application mode" );
            }
        }

        this.status = STATUS.OPEN;
    }

    @Override
    public void close( )
    {
        if ( syslogManager != null )
        {
            syslogManager.close();
        }

        if ( auditVault != null )
        {
            auditVault.close();
        }

        this.status = STATUS.CLOSED;
    }

    @Override
    public List<HealthRecord> healthCheck( )
    {
        if ( status != STATUS.OPEN )
        {
            return Collections.emptyList();
        }

        final List<HealthRecord> healthRecords = new ArrayList<>();
        if ( syslogManager != null )
        {
            healthRecords.addAll( syslogManager.healthCheck() );
        }

        if ( lastError != null )
        {
            healthRecords.add( HealthRecord.forMessage(
                    HealthMessage.ServiceClosed,
                    this.getClass().getSimpleName(),
                    lastError.toDebugStr() ) );
        }

        return Collections.unmodifiableList( healthRecords );
    }

    public Iterator<AuditRecord> readVault( )
    {
        return auditVault.readVault();
    }

    public List<UserAuditRecord> readUserHistory( final PwmSession pwmSession )
            throws PwmUnrecoverableException
    {
        return readUserHistory( pwmSession.getLabel(), pwmSession.getUserInfo() );
    }

    public List<UserAuditRecord> readUserHistory( final SessionLabel sessionLabel, final UserInfo userInfoBean )
            throws PwmUnrecoverableException
    {
        final Instant startTime = Instant.now();
        final List<UserAuditRecord> results = userHistoryStore.readUserHistory( sessionLabel, userInfoBean );
        LOGGER.trace( sessionLabel, () -> "read " + results.size() + " user history records", () -> TimeDuration.fromCurrent( startTime ) );
        return results;
    }

    private void sendAsEmail( final AuditRecord record )
            throws PwmUnrecoverableException
    {
        if ( record == null || record.getEventCode() == null )
        {
            return;
        }
        if ( settings.getAlertFromAddress() == null || settings.getAlertFromAddress().length() < 1 )
        {
            return;
        }

        switch ( record.getEventCode().getType() )
        {
            case SYSTEM:
                for ( final String toAddress : settings.getSystemEmailAddresses() )
                {
                    sendAsEmail( pwmApplication, record, toAddress, settings.getAlertFromAddress() );
                }
                break;

            case USER:
            case HELPDESK:
                for ( final String toAddress : settings.getUserEmailAddresses() )
                {
                    sendAsEmail( pwmApplication, record, toAddress, settings.getAlertFromAddress() );
                }
                break;

            default:
                JavaHelper.unhandledSwitchStatement( record.getEventCode().getType() );

        }
    }

    private static void sendAsEmail(
            final PwmApplication pwmApplication,
            final AuditRecord record,
            final String toAddress,
            final String fromAddress

    )
            throws PwmUnrecoverableException
    {
        final MacroRequest macroRequest = MacroRequest.forNonUserSpecific( pwmApplication, SessionLabel.AUDITING_SESSION_LABEL );

        String subject = macroRequest.expandMacros( pwmApplication.getConfig().readAppProperty( AppProperty.AUDIT_EVENTS_EMAILSUBJECT ) );
        subject = subject.replace( "%EVENT%", record.getEventCode().getLocalizedString( pwmApplication.getConfig(), PwmConstants.DEFAULT_LOCALE ) );

        final String body;
        {
            final String jsonRecord = JsonUtil.serialize( record );
            final Map<String, Object> mapRecord = JsonUtil.deserializeMap( jsonRecord );
            body = StringUtil.mapToString( mapRecord, "=", "\n" );
        }

        final EmailItemBean emailItem = EmailItemBean.builder()
                .to( toAddress )
                .from( fromAddress )
                .subject( subject )
                .bodyPlain( body )
                .build();
        pwmApplication.getEmailQueue().submitEmail( emailItem, null, macroRequest );
    }

    public Instant eldestVaultRecord( )
    {
        if ( status != STATUS.OPEN || auditVault == null )
        {
            return null;
        }

        return auditVault.oldestRecord();
    }

    public String sizeToDebugString( )
    {
        return auditVault == null
                ? LocaleHelper.getLocalizedMessage( PwmConstants.DEFAULT_LOCALE, Display.Value_NotApplicable, null )
                : auditVault.sizeToDebugString();
    }

    public void submit( final AuditEvent auditEvent, final UserInfo userInfo, final PwmSession pwmSession )
            throws PwmUnrecoverableException
    {
        final AuditRecordFactory auditRecordFactory = new AuditRecordFactory( pwmApplication, pwmSession.getSessionManager().getMacroMachine( ) );
        final UserAuditRecord auditRecord = auditRecordFactory.createUserAuditRecord( auditEvent, userInfo, pwmSession );
        submit( pwmSession.getLabel(), auditRecord );
    }

    public void submit( final SessionLabel sessionLabel, final AuditRecord auditRecord )
            throws PwmUnrecoverableException
    {

        final String jsonRecord = JsonUtil.serialize( auditRecord );

        if ( status != STATUS.OPEN )
        {
            LOGGER.debug( sessionLabel, () -> "discarding audit event (AuditManager is not open); event=" + jsonRecord );
            return;
        }

        if ( auditRecord.getEventCode() == null )
        {
            LOGGER.error( sessionLabel, () -> "discarding audit event, missing event type; event=" + jsonRecord );
            return;
        }

        if ( !settings.getPermittedEvents().contains( auditRecord.getEventCode() ) )
        {
            LOGGER.debug( () -> "discarding event, " + auditRecord.getEventCode() + " are being ignored; event=" + jsonRecord );
            return;
        }

        // add to debug log
        LOGGER.info( sessionLabel, () -> "audit event: " + jsonRecord );

        // add to audit db
        if ( auditVault != null )
        {
            try
            {
                auditVault.add( auditRecord );
            }
            catch ( final PwmOperationalException e )
            {
                LOGGER.warn( sessionLabel, () -> "discarding audit event due to storage error: " + e.getMessage() );
            }
        }

        // email alert
        sendAsEmail( auditRecord );

        // add to user history record
        if ( auditRecord instanceof UserAuditRecord )
        {
            if ( settings.getUserStoredEvents().contains( auditRecord.getEventCode() ) )
            {
                final String perpetratorDN = ( ( UserAuditRecord ) auditRecord ).getPerpetratorDN();
                if ( !StringUtil.isEmpty( perpetratorDN ) )
                {
                    userHistoryStore.updateUserHistory( sessionLabel, ( UserAuditRecord ) auditRecord );
                }
                else
                {
                    LOGGER.trace( sessionLabel, () -> "skipping update of user history, audit record does not have a perpetratorDN: " + JsonUtil.serialize( auditRecord ) );
                }
            }
        }

        // send to syslog
        if ( syslogManager != null )
        {
            try
            {
                syslogManager.add( auditRecord );
            }
            catch ( final PwmOperationalException e )
            {
                lastError = e.getErrorInformation();
            }
        }

        // update statistics
        StatisticsManager.incrementStat( pwmApplication, Statistic.AUDIT_EVENTS );
    }


    public int outputVaultToCsv( final OutputStream outputStream, final Locale locale, final boolean includeHeader )
            throws IOException
    {
        final Configuration config = null;

        final CSVPrinter csvPrinter = JavaHelper.makeCsvPrinter( outputStream );

        csvPrinter.printComment( " " + PwmConstants.PWM_APP_NAME + " audit record output " );
        csvPrinter.printComment( " " + JavaHelper.toIsoDate( Instant.now() ) );

        if ( includeHeader )
        {
            final List<String> headers = new ArrayList<>();
            headers.add( "Type" );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_EventCode", config, password.pwm.i18n.Admin.class ) );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_Timestamp", config, password.pwm.i18n.Admin.class ) );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_GUID", config, password.pwm.i18n.Admin.class ) );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_Message", config, password.pwm.i18n.Admin.class ) );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_Instance", config, password.pwm.i18n.Admin.class ) );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_PerpetratorID", config, password.pwm.i18n.Admin.class ) );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_PerpetratorDN", config, password.pwm.i18n.Admin.class ) );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_TargetID", config, password.pwm.i18n.Admin.class ) );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_TargetDN", config, password.pwm.i18n.Admin.class ) );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_SourceAddress", config, password.pwm.i18n.Admin.class ) );
            headers.add( LocaleHelper.getLocalizedMessage( locale, "Field_Audit_SourceHost", config, password.pwm.i18n.Admin.class ) );
            csvPrinter.printRecord( headers );
        }

        int counter = 0;
        for ( final Iterator<AuditRecord> recordIterator = readVault(); recordIterator.hasNext(); )
        {
            final AuditRecord loopRecord = recordIterator.next();
            counter++;

            final List<String> lineOutput = new ArrayList<>();
            lineOutput.add( loopRecord.getEventCode().getType().toString() );
            lineOutput.add( loopRecord.getEventCode().toString() );
            lineOutput.add( JavaHelper.toIsoDate( loopRecord.getTimestamp() ) );
            lineOutput.add( loopRecord.getGuid() );
            lineOutput.add( loopRecord.getMessage() == null ? "" : loopRecord.getMessage() );
            if ( loopRecord instanceof SystemAuditRecord )
            {
                lineOutput.add( ( ( SystemAuditRecord ) loopRecord ).getInstance() );
            }
            if ( loopRecord instanceof UserAuditRecord )
            {
                lineOutput.add( ( ( UserAuditRecord ) loopRecord ).getPerpetratorID() );
                lineOutput.add( ( ( UserAuditRecord ) loopRecord ).getPerpetratorDN() );
                lineOutput.add( "" );
                lineOutput.add( "" );
                lineOutput.add( ( ( UserAuditRecord ) loopRecord ).getSourceAddress() );
                lineOutput.add( ( ( UserAuditRecord ) loopRecord ).getSourceHost() );
            }
            if ( loopRecord instanceof HelpdeskAuditRecord )
            {
                lineOutput.add( ( ( HelpdeskAuditRecord ) loopRecord ).getPerpetratorID() );
                lineOutput.add( ( ( HelpdeskAuditRecord ) loopRecord ).getPerpetratorDN() );
                lineOutput.add( ( ( HelpdeskAuditRecord ) loopRecord ).getTargetID() );
                lineOutput.add( ( ( HelpdeskAuditRecord ) loopRecord ).getTargetDN() );
                lineOutput.add( ( ( HelpdeskAuditRecord ) loopRecord ).getSourceAddress() );
                lineOutput.add( ( ( HelpdeskAuditRecord ) loopRecord ).getSourceHost() );
            }
            csvPrinter.printRecord( lineOutput );
        }
        csvPrinter.flush();

        return counter;
    }

    @Override
    public ServiceInfoBean serviceInfo( )
    {
        return serviceInfo;
    }

    public int syslogQueueSize( )
    {
        return syslogManager != null ? syslogManager.queueSize() : 0;
    }
}
