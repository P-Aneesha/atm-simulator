let token = null, mode = '';
const $ = id => document.getElementById(id);
const money = n => '₹' + Number(n).toFixed(2);

async function api(path, data = {}) {
  const res = await fetch('/api/' + path, { method: 'POST', body: new URLSearchParams({ token, ...data }) });
  const j = await res.json();
  if (!res.ok) throw new Error(j.error);
  return j;
}
function show(id) {
  document.querySelectorAll('.screen:not(#msgbox)').forEach(s => s.classList.add('hidden'));
  $(id).classList.remove('hidden');
  msg('');
}
function msg(t, ok) { $('msg').textContent = t; $('msg').className = ok ? '' : 'err'; }
async function run(fn) { try { await fn(); } catch (e) { msg(e.message); } }

const login = () => run(async () => {
  const j = await api('login', { account: $('acc').value, pin: $('pin').value });
  token = j.token; $('hello').textContent = 'Hello, ' + j.name; $('pin').value = ''; show('menu');
});
const register = () => run(async () => {
  const j = await api('register', { name: $('rname').value, pin: $('rpin').value });
  $('acc').value = j.account; show('login');
  msg('Account created! Your number is ' + j.account + ' — remember it.', true);
});
const showBalance = () => run(async () => msg('Balance: ' + money((await api('balance')).balance), true));
function startMove(m) { mode = m; $('atitle').textContent = m === 'deposit' ? 'Deposit' : 'Withdraw'; $('amt').value = ''; show('amount'); }
const doMove = () => run(async () => {
  const j = await api(mode, { amount: $('amt').value });
  show('menu'); msg('Done! New balance: ' + money(j.balance), true);
});
const history = () => run(async () => {
  const j = await api('history');
  $('tbl').innerHTML = '<tr><th>Type</th><th>Amount</th><th>Balance</th><th>Time</th></tr>' +
    j.items.map(i => `<tr><td>${i.type}</td><td>${money(i.amount)}</td><td>${money(i.balance)}</td><td>${i.time.slice(0, 16)}</td></tr>`).join('');
  show('hist');
});
const logout = () => run(async () => { await api('logout'); token = null; show('login'); });
