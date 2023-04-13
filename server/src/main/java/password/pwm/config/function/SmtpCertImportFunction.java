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

package password.pwm.config.function;

import password.pwm.bean.UserIdentity;
import password.pwm.config.Configuration;
import password.pwm.config.PwmSetting;
import password.pwm.config.SettingUIFunction;
import password.pwm.config.stored.StoredConfigurationModifier;
import password.pwm.config.value.X509CertificateValue;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.http.PwmRequest;
import password.pwm.http.PwmSession;
import password.pwm.i18n.Message;
import password.pwm.svc.email.EmailServerUtil;
import password.pwm.util.java.JavaHelper;

import java.security.cert.X509Certificate;
import java.util.List;

public class SmtpCertImportFunction implements SettingUIFunction
{
    @Override
    public String provideFunction(
            final PwmRequest pwmRequest,
            final StoredConfigurationModifier modifier,
            final PwmSetting setting,
            final String profile,
            final String extraData
    )
            throws PwmUnrecoverableException
    {
        final PwmSession pwmSession = pwmRequest.getPwmSession();

        final Configuration configuration = new Configuration( modifier.newStoredConfiguration() );
        final List<X509Certificate> certs = EmailServerUtil.readCertificates( configuration, profile );
        if ( !JavaHelper.isEmpty( certs ) )
        {
            final UserIdentity userIdentity = pwmSession.isAuthenticated() ? pwmSession.getUserInfo().getUserIdentity() : null;
            modifier.writeSetting( PwmSetting.EMAIL_SERVER_CERTS, profile, X509CertificateValue.fromX509( certs ), userIdentity );
        }

        return Message.getLocalizedMessage( pwmSession.getSessionStateBean().getLocale(), Message.Success_Unknown, pwmRequest.getConfig() );
    }

}
