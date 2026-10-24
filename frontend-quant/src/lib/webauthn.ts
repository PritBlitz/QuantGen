/**
 * WebAuthn helpers (PRD F-SEC-1/2). The server sends options as JSON with base64url strings for
 * binary fields; the browser API wants ArrayBuffers, and the credential goes back as JSON. Modern
 * browsers ship `PublicKeyCredential.parse*OptionsFromJSON` and `credential.toJSON()`; this file
 * uses them when present and falls back to a small converter, so no dependency is needed.
 */

export const webauthnSupported = () =>
  typeof window !== 'undefined' &&
  typeof window.PublicKeyCredential === 'function' &&
  !!navigator.credentials

function b64urlToBuffer(value: string): ArrayBuffer {
  const b64 = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4)
  const bin = atob(padded)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return bytes.buffer
}

function bufferToB64url(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

type Json = Record<string, unknown>
type CredDescriptor = { id: string; type: string; transports?: string[] }

interface PkcStatic {
  parseCreationOptionsFromJSON?: (o: Json) => PublicKeyCredentialCreationOptions
  parseRequestOptionsFromJSON?: (o: Json) => PublicKeyCredentialRequestOptions
}

const descriptors = (list: unknown) =>
  Array.isArray(list)
    ? (list as CredDescriptor[]).map((d) => ({ ...d, id: b64urlToBuffer(d.id) }))
    : undefined

function creationFromJson(o: Json): PublicKeyCredentialCreationOptions {
  const native = (window.PublicKeyCredential as unknown as PkcStatic).parseCreationOptionsFromJSON
  if (native) return native(o)
  const user = o.user as Json
  return {
    ...(o as unknown as PublicKeyCredentialCreationOptions),
    challenge: b64urlToBuffer(String(o.challenge)),
    user: {
      ...(user as unknown as PublicKeyCredentialUserEntity),
      id: b64urlToBuffer(String(user.id)),
    },
    excludeCredentials: descriptors(o.excludeCredentials) as
      PublicKeyCredentialDescriptor[] | undefined,
  }
}

function requestFromJson(o: Json): PublicKeyCredentialRequestOptions {
  const native = (window.PublicKeyCredential as unknown as PkcStatic).parseRequestOptionsFromJSON
  if (native) return native(o)
  return {
    ...(o as unknown as PublicKeyCredentialRequestOptions),
    challenge: b64urlToBuffer(String(o.challenge)),
    allowCredentials: descriptors(o.allowCredentials) as
      PublicKeyCredentialDescriptor[] | undefined,
  }
}

function credentialToJson(cred: PublicKeyCredential): Json {
  const withJson = cred as unknown as { toJSON?: () => Json }
  if (typeof withJson.toJSON === 'function') return withJson.toJSON()
  const r = cred.response as AuthenticatorAttestationResponse & AuthenticatorAssertionResponse
  const response: Json = { clientDataJSON: bufferToB64url(r.clientDataJSON) }
  if ('attestationObject' in r && r.attestationObject)
    response.attestationObject = bufferToB64url(r.attestationObject)
  if ('authenticatorData' in r && r.authenticatorData)
    response.authenticatorData = bufferToB64url(r.authenticatorData)
  if ('signature' in r && r.signature) response.signature = bufferToB64url(r.signature)
  if ('userHandle' in r && r.userHandle) response.userHandle = bufferToB64url(r.userHandle)
  return {
    id: cred.id,
    rawId: bufferToB64url(cred.rawId),
    type: cred.type,
    response,
    clientExtensionResults: cred.getClientExtensionResults(),
  }
}

/** Create a passkey. Returns null if the user dismisses the browser prompt. */
export async function createPasskey(options: Json): Promise<Json | null> {
  try {
    const cred = await navigator.credentials.create({ publicKey: creationFromJson(options) })
    return cred ? credentialToJson(cred as PublicKeyCredential) : null
  } catch (e) {
    if (e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'AbortError'))
      return null
    throw e
  }
}

/** Sign a challenge with an existing passkey. Returns null if the user dismisses the prompt. */
export async function getPasskeyAssertion(options: Json): Promise<Json | null> {
  try {
    const cred = await navigator.credentials.get({ publicKey: requestFromJson(options) })
    return cred ? credentialToJson(cred as PublicKeyCredential) : null
  } catch (e) {
    if (e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'AbortError'))
      return null
    throw e
  }
}
